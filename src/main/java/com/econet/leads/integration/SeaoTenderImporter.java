package com.econet.leads.integration;

import com.econet.leads.integration.support.HttpDownloader;
import com.econet.leads.integration.support.ImportConfig;
import com.econet.leads.integration.support.ImportFiles;
import com.econet.leads.integration.support.ImportValues;
import com.econet.leads.integration.support.TextMatcher;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SEAO (Système électronique d'appel d'offres du Québec) open data on Données Québec, dataset
 * "systeme-electronique-dappel-doffres-seao": monthly ("mensuel_YYYYMMDD_YYYYMMDD.json") and weekly
 * ("hebdo_...") files, each an OCDS release package {@code {"publishedDate", "releases": [...]}}.
 *
 * <p>Files are discovered with CKAN package_show (or listed in config fileUrls), downloaded to disk
 * and parsed with a streaming JSON parser: one release is materialized at a time, so 20k-release
 * monthly files never sit in memory. SEAO conventions (per parsers verified on real files):
 * the notice number is the OCID tail ("ocds-ec9k95-1740136"), tender.id is the buyer's own
 * reference, buyer in {@code buyer}/parties[roles=buyer], SEAO category in tender.items[].description,
 * winners in awards[].suppliers with awards[].contractPeriod.
 *
 * <p>Keeps releases whose title/description/items match the keywords; awarded ones are kept as
 * noticeType AWARD with the supplier, category "Contrat octroyé – ..." and closingAt = contract
 * END date (renewal prospects; the CRM shows "fin du contrat dans N mois"). Upsert by OCID.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeaoTenderImporter {

    public static final String IMPORTER = "SEAO_TENDERS";
    /** Category prefix of awarded contracts; the CRM matches /octroy|adjug|award/i. */
    public static final String AWARD_CATEGORY = "Contrat octroyé";
    private static final Pattern FILE_DATES = Pattern.compile("(\\d{8})_(\\d{8})");

    private final HttpDownloader downloader;
    private final CkanApiClient ckanApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    record FileRule(String prefix, int latest) {
    }

    record Settings(String ckanBaseUrl, String packageId, List<FileRule> files, List<String> fileUrls,
                    TextMatcher keywords, String noticeUrlTemplate, boolean includeAwards) {
    }

    public Settings parse(DataSource source) {
        ImportConfig c = new ImportConfig(source.getSourceName(), source.getConfig());
        List<FileRule> rules = new ArrayList<>();
        for (ImportConfig r : c.sections("files")) {
            rules.add(new FileRule(r.requireString("prefix"), Math.max(1, r.integer("latest", 1))));
        }
        if (rules.isEmpty()) {
            rules = List.of(new FileRule("mensuel", 2), new FileRule("hebdo", 4));
        }
        return new Settings(
                c.string("ckanBaseUrl", "https://www.donneesquebec.ca/recherche/api/3/action/"),
                c.string("packageId", "systeme-electronique-dappel-doffres-seao"),
                rules,
                c.strings("fileUrls", List.of()),
                new TextMatcher(c.strings("keywords", CanadaBuysTenderImporter.DEFAULT_KEYWORDS)),
                c.string("noticeUrlTemplate", "https://seao.gouv.qc.ca/avis-du-jour"),
                c.bool("includeAwards", true));
    }

    public RecordStream<Tender> stream(Settings s, ImportReport report) {
        return sink -> {
            List<String> urls = s.fileUrls().isEmpty() ? discover(s, report) : s.fileUrls();
            Counts counts = new Counts();
            for (String url : urls) {
                Path file = downloader.downloadToTempFile(url, ".json", Duration.ofMinutes(20));
                try (InputStream in = Files.newInputStream(file)) {
                    if (!read(in, s, counts, sink)) return;
                } finally {
                    ImportFiles.deleteQuietly(file);
                }
            }
            finish(counts, report);
        };
    }

    /** Latest files per prefix from CKAN package_show, oldest first so newer releases are applied last. */
    List<String> discover(Settings s, ImportReport report) {
        JsonNode pkg = ckanApiClient.action(s.ckanBaseUrl(), "package_show", Map.of("id", s.packageId()));
        List<JsonNode> resources = new ArrayList<>();
        pkg.path("resources").forEach(resources::add);
        List<JsonNode> selected = new ArrayList<>();
        for (FileRule rule : s.files()) {
            String prefix = rule.prefix().toLowerCase(Locale.ROOT);
            resources.stream()
                    .filter(r -> "json".equalsIgnoreCase(r.path("format").asText(""))
                            || r.path("url").asText("").toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(r -> fileName(r).startsWith(prefix))
                    .sorted(Comparator.comparing(SeaoTenderImporter::sortKey).reversed())
                    .limit(rule.latest())
                    .forEach(selected::add);
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException("SEAO: no JSON resource named " + s.files().stream().map(FileRule::prefix).toList()
                    + "* in CKAN package " + s.packageId() + " (" + resources.size() + " resources). "
                    + "Set 'files' or 'fileUrls' in the data source config.");
        }
        selected.sort(Comparator.comparing(SeaoTenderImporter::sortKey));
        List<String> urls = selected.stream().map(r -> r.path("url").asText()).toList();
        report.note("SEAO files: " + selected.stream().map(SeaoTenderImporter::fileName).toList());
        return urls;
    }

    private static String fileName(JsonNode resource) {
        String url = resource.path("url").asText("");
        String fromUrl = url.replaceAll("^.*/", "");
        String name = resource.path("name").asText("");
        return (FILE_DATES.matcher(fromUrl).find() ? fromUrl : name.isEmpty() ? fromUrl : name).toLowerCase(Locale.ROOT);
    }

    private static String sortKey(JsonNode resource) {
        Matcher m = FILE_DATES.matcher(fileName(resource));
        String dates = m.find() ? m.group(2) + m.group(1) : "";
        return dates + "|" + resource.path("last_modified").asText(resource.path("created").asText(""));
    }

    static final class Counts {
        long releases;
        long matched;
        long kept;
        long awards;
    }

    /**
     * Streams one OCDS file. Accepts a release package {"releases": [...]} or a bare array.
     *
     * @return false if the sink asked to stop
     */
    boolean read(InputStream in, Settings s, Counts counts, Predicate<Tender> sink) throws IOException {
        JsonFactory factory = objectMapper.getFactory();
        try (JsonParser p = factory.createParser(in)) {
            JsonToken first = p.nextToken();
            if (first == JsonToken.START_ARRAY) {
                return readReleases(p, s, counts, sink);
            }
            if (first != JsonToken.START_OBJECT) {
                throw new IllegalStateException("SEAO file is not an OCDS JSON package (starts with " + first + ")");
            }
            boolean sawReleases = false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                JsonToken value = p.nextToken();
                if ("releases".equals(field) && value == JsonToken.START_ARRAY) {
                    sawReleases = true;
                    if (!readReleases(p, s, counts, sink)) return false;
                } else {
                    p.skipChildren();
                }
            }
            if (!sawReleases) {
                throw new IllegalStateException("SEAO file has no 'releases' array (expected an OCDS release package)");
            }
            return true;
        }
    }

    private boolean readReleases(JsonParser p, Settings s, Counts counts, Predicate<Tender> sink) throws IOException {
        while (p.nextToken() == JsonToken.START_OBJECT) {
            JsonNode release = objectMapper.readTree(p);
            counts.releases++;
            Tender t = map(release, s, counts);
            if (t != null) {
                counts.kept++;
                if (!sink.test(t)) return false;
            }
        }
        return true;
    }

    Tender map(JsonNode r, Settings s, Counts counts) {
        String ocid = text(r.path("ocid"));
        if (ocid == null) return null;
        JsonNode tender = r.path("tender");
        List<String> itemDescriptions = new ArrayList<>();
        tender.path("items").forEach(i -> {
            String d = text(i.path("description"));
            if (d != null) itemDescriptions.add(d);
            String c = text(i.path("classification").path("description"));
            if (c != null) itemDescriptions.add(c);
        });
        List<String> texts = new ArrayList<>();
        texts.add(text(tender.path("title")));
        texts.add(text(tender.path("description")));
        texts.addAll(itemDescriptions);
        r.path("awards").forEach(a -> {
            texts.add(text(a.path("title")));
            texts.add(text(a.path("description")));
        });
        List<String> matched = s.keywords().matches(texts.toArray(new String[0]));
        if (matched.isEmpty()) return null;
        counts.matched++;
        if ("cancelled".equalsIgnoreCase(text(tender.path("status")))) return null;

        Set<String> suppliers = new LinkedHashSet<>();
        LocalDateTime contractEnd = null;
        for (JsonNode a : r.path("awards")) {
            a.path("suppliers").forEach(sp -> {
                String n = text(sp.path("name"));
                if (n != null) suppliers.add(n);
            });
            contractEnd = latest(contractEnd, ImportValues.dateTime(text(a.path("contractPeriod").path("endDate"))));
        }
        for (JsonNode c : r.path("contracts")) {
            contractEnd = latest(contractEnd, ImportValues.dateTime(text(c.path("period").path("endDate"))));
        }
        boolean award = !suppliers.isEmpty();
        if (award && !s.includeAwards()) return null;
        if (award) counts.awards++;

        Tender t = new Tender();
        t.setSource(TenderSource.SEAO);
        t.setExternalId(ocid);
        String title = text(tender.path("title"));
        if (title == null && r.path("awards").size() > 0) title = text(r.path("awards").get(0).path("title"));
        t.setTitle(ImportValues.truncate(title != null ? title : ocid, 1000));
        t.setDescription(ImportValues.truncate(text(tender.path("description")), 4000));
        JsonNode buyerParty = buyerParty(r);
        String buyer = text(r.path("buyer").path("name"));
        if (buyer == null && buyerParty != null) buyer = text(buyerParty.path("name"));
        t.setBuyer(ImportValues.truncate(buyer, 500));
        if (buyerParty != null) {
            String region = text(buyerParty.path("address").path("region"));
            String locality = text(buyerParty.path("address").path("locality"));
            t.setRegion(ImportValues.truncate(region != null ? region : locality, 500));
        }
        String category = !itemDescriptions.isEmpty() ? itemDescriptions.get(0) : text(tender.path("mainProcurementCategory"));
        // CRM convention (no dedicated DTO field): awarded contracts are recognised by a category
        // containing "Contrat octroyé", and their closingAt is the contract END date (renewal).
        if (award) {
            category = category == null ? AWARD_CATEGORY : AWARD_CATEGORY + " – " + category;
        }
        t.setCategory(ImportValues.truncate(category, 255));
        LocalDateTime releaseDate = ImportValues.dateTime(text(r.path("date")));
        LocalDateTime start = ImportValues.dateTime(text(tender.path("tenderPeriod").path("startDate")));
        t.setPublishedAt(start != null ? start : releaseDate);
        LocalDateTime bidClosing = ImportValues.dateTime(text(tender.path("tenderPeriod").path("endDate")));
        t.setClosingAt(award && contractEnd != null ? contractEnd : bidClosing);
        // The notice page (".../avis-resultat-recherche/consulter?ItemId=<uuid>") is published as a
        // tender document; prefer a seao.gouv.qc.ca link over attachments hosted elsewhere.
        String url = null;
        for (JsonNode d : tender.path("documents")) {
            String u = text(d.path("url"));
            if (u == null) continue;
            if (u.contains("seao.gouv.qc.ca")) {
                url = u;
                break;
            }
            if (url == null) url = u;
        }
        t.setUrl(url != null ? url : s.noticeUrlTemplate()
                .replace("{ocid}", ocid)
                .replace("{number}", ocid.substring(ocid.lastIndexOf('-') + 1)));
        var value = ImportValues.amount(text(tender.path("value").path("amount")));
        if (value == null && r.path("awards").size() > 0) {
            value = ImportValues.amount(text(r.path("awards").get(0).path("value").path("amount")));
        }
        t.setEstimatedValue(value);
        t.setMatchedKeywords(new ArrayList<>(matched));
        t.setNoticeType(award ? "AWARD" : "TENDER");
        t.setAwardedTo(award ? ImportValues.truncate(String.join(", ", suppliers), 500) : null);
        t.setContractEndAt(contractEnd);
        t.setSourceReleaseAt(releaseDate);
        return t;
    }

    void finish(Counts counts, ImportReport report) {
        if (counts.releases == 0) {
            throw new IllegalStateException("SEAO files contained no releases");
        }
        report.note(String.format(Locale.ROOT, "%d releases read, %d matched keywords, %d kept (%d awarded contracts)",
                counts.releases, counts.matched, counts.kept, counts.awards));
    }

    private static JsonNode buyerParty(JsonNode r) {
        String buyerId = text(r.path("buyer").path("id"));
        JsonNode byRole = null;
        for (JsonNode party : r.path("parties")) {
            if (buyerId != null && buyerId.equals(text(party.path("id")))) return party;
            for (JsonNode role : party.path("roles")) {
                if (byRole == null && ("buyer".equals(role.asText()) || "procuringEntity".equals(role.asText()))) {
                    byRole = party;
                }
            }
        }
        return byRole;
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
