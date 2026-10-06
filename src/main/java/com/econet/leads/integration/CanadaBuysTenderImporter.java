package com.econet.leads.integration;

import com.econet.leads.integration.support.CsvSupport;
import com.econet.leads.integration.support.HttpDownloader;
import com.econet.leads.integration.support.ImportConfig;
import com.econet.leads.integration.support.ImportFiles;
import com.econet.leads.integration.support.ImportValues;
import com.econet.leads.integration.support.TextMatcher;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.opencsv.CSVReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * CanadaBuys open tender notices (openTenderNotice-ouvertAvisAppelOffres.csv, refreshed daily).
 *
 * <p>Format: bilingual UTF-8 CSV with BOM, ~70 columns named "<english>-<french>[-eng|-fra]";
 * multi-valued cells (regions, categories, UNSPSC) are "*value" lines separated by newlines.
 * Column names verified against several open-source parsers of the live file (see
 * fixtures/canadabuys-open-tenders.csv header comment and the import report). Keeps notices whose
 * title/description (EN or FR) match the configured keywords and whose region of delivery or
 * opportunity mentions Quebec or is national ("Canada").
 */
@Component
@RequiredArgsConstructor
public class CanadaBuysTenderImporter {

    public static final String IMPORTER = "CANADABUYS_TENDERS";
    public static final List<String> DEFAULT_KEYWORDS = List.of(
            "nettoyage", "entretien ménager", "conciergerie", "janitorial", "custodial", "cleaning", "housekeeping");

    static final Map<String, String> DEFAULT_COLUMNS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("referenceNumber", "referenceNumber-numeroReference");
        m.put("solicitationNumber", "solicitationNumber-numeroSollicitation");
        m.put("titleEn", "title-titre-eng");
        m.put("titleFr", "title-titre-fra");
        m.put("descriptionEn", "tenderDescription-descriptionAppelOffres-eng");
        m.put("descriptionFr", "tenderDescription-descriptionAppelOffres-fra");
        m.put("publishedAt", "publicationDate-datePublication");
        m.put("closingAt", "tenderClosingDate-appelOffresDateCloture");
        m.put("status", "tenderStatus-appelOffresStatut-eng");
        m.put("category", "procurementCategory-categorieApprovisionnement");
        m.put("noticeType", "noticeType-avisType-eng");
        m.put("regionsOfDelivery", "regionsOfDelivery-regionsLivraison-eng");
        m.put("regionsOfOpportunity", "regionsOfOpportunity-regionAppelOffres-eng");
        m.put("buyerEn", "contractingEntityName-nomEntitContractante-eng");
        m.put("buyerFr", "contractingEntityName-nomEntitContractante-fra");
        m.put("urlEn", "noticeURL-URLavis-eng");
        m.put("urlFr", "noticeURL-URLavis-fra");
        m.put("unspscDescription", "unspscDescription-eng");
        DEFAULT_COLUMNS = java.util.Collections.unmodifiableMap(m);
    }
    static final List<String> DEFAULT_REQUIRED = List.of("solicitationNumber", "titleEn", "closingAt", "urlEn",
            "regionsOfDelivery", "descriptionEn");

    static final Map<String, String> CATEGORY_LABELS = Map.of(
            "SRV", "Services", "GD", "Biens", "CNST", "Construction", "SRVTGD", "Services liés aux biens");

    private final HttpDownloader downloader;

    record Settings(String url, Map<String, String> columns, List<String> required, TextMatcher keywords,
                    TextMatcher regions, boolean keepWhenRegionMissing, TextMatcher excludedStatuses) {
    }

    public Settings parse(DataSource source) {
        ImportConfig c = new ImportConfig(source.getSourceName(), source.getConfig());
        Map<String, String> columns = new LinkedHashMap<>(DEFAULT_COLUMNS);
        c.section("columns").raw().forEach((k, v) -> columns.put(k, v == null ? null : v.toString()));
        return new Settings(
                c.string("csvUrl", "https://canadabuys.canada.ca/opendata/pub/openTenderNotice-ouvertAvisAppelOffres.csv"),
                columns,
                c.strings("requiredColumns", DEFAULT_REQUIRED),
                new TextMatcher(c.strings("keywords", DEFAULT_KEYWORDS)),
                new TextMatcher(c.strings("regionKeywords", List.of("quebec", "canada"))),
                c.bool("keepWhenRegionMissing", true),
                new TextMatcher(c.strings("excludedStatuses", List.of("cancelled", "annule", "expired", "expire"))));
    }

    public RecordStream<Tender> stream(Settings s, ImportReport report) {
        return sink -> {
            Path file = downloader.downloadToTempFile(s.url(), ".csv", Duration.ofMinutes(10));
            try (InputStream in = Files.newInputStream(file)) {
                read(in, s, report, sink);
            } finally {
                ImportFiles.deleteQuietly(file);
            }
        };
    }

    void read(InputStream in, Settings s, ImportReport report, Predicate<Tender> sink) throws IOException {
        long rows = 0, keywordMatches = 0, kept = 0;
        try (CSVReader reader = CsvSupport.open(in, StandardCharsets.UTF_8, ',')) {
            Map<String, Integer> cols = CsvSupport.resolve("CanadaBuys CSV", CsvSupport.readNext(reader), s.columns(), s.required());
            String[] row;
            while ((row = CsvSupport.readNext(reader)) != null) {
                if (row.length == 1 && (row[0] == null || row[0].isBlank())) continue;
                rows++;
                String titleEn = CsvSupport.get(row, cols, "titleEn");
                String titleFr = CsvSupport.get(row, cols, "titleFr");
                String descEn = CsvSupport.get(row, cols, "descriptionEn");
                String descFr = CsvSupport.get(row, cols, "descriptionFr");
                List<String> matched = s.keywords().matches(titleEn, titleFr, descEn, descFr,
                        CsvSupport.get(row, cols, "unspscDescription"));
                if (matched.isEmpty()) continue;
                keywordMatches++;

                if (s.excludedStatuses().matchesAny(CsvSupport.get(row, cols, "status"))) continue;
                String delivery = multi(CsvSupport.get(row, cols, "regionsOfDelivery"));
                String opportunity = multi(CsvSupport.get(row, cols, "regionsOfOpportunity"));
                if (delivery == null && opportunity == null) {
                    if (!s.keepWhenRegionMissing()) continue;
                } else if (!s.regions().matchesAny(delivery, opportunity)) {
                    continue;
                }

                String id = CsvSupport.get(row, cols, "solicitationNumber");
                if (id == null) id = CsvSupport.get(row, cols, "referenceNumber");
                String url = CsvSupport.get(row, cols, "urlFr") != null && preferFrench(titleFr)
                        ? CsvSupport.get(row, cols, "urlFr") : CsvSupport.get(row, cols, "urlEn");
                if (id == null || url == null) continue;

                Tender t = new Tender();
                t.setSource(TenderSource.CANADABUYS);
                t.setExternalId(id);
                t.setTitle(ImportValues.truncate(titleFr != null ? titleFr : titleEn, 1000));
                String desc = descFr != null ? descFr : descEn;
                t.setDescription(ImportValues.truncate(desc, 4000));
                String buyer = CsvSupport.get(row, cols, "buyerFr") != null ? CsvSupport.get(row, cols, "buyerFr") : CsvSupport.get(row, cols, "buyerEn");
                t.setBuyer(ImportValues.truncate(buyer, 500));
                t.setRegion(ImportValues.truncate(delivery != null ? delivery : opportunity, 500));
                t.setCategory(category(CsvSupport.get(row, cols, "category")));
                t.setPublishedAt(ImportValues.dateTime(CsvSupport.get(row, cols, "publishedAt")));
                t.setClosingAt(ImportValues.dateTime(CsvSupport.get(row, cols, "closingAt")));
                t.setUrl(url);
                t.setMatchedKeywords(new ArrayList<>(matched));
                t.setNoticeType("TENDER");
                t.setSourceReleaseAt(t.getPublishedAt());
                kept++;
                if (!sink.test(t)) return;
            }
        }
        if (rows == 0) {
            throw new IllegalStateException("The CanadaBuys CSV has a header but no rows");
        }
        report.note(String.format(Locale.ROOT, "%d notices read, %d matched keywords, %d kept (Quebec/national, not cancelled)",
                rows, keywordMatches, kept));
    }

    private static boolean preferFrench(String titleFr) {
        return titleFr != null;
    }

    /** "*Quebec (except NCR)\n*Canada" -> "Quebec (except NCR), Canada". */
    static String multi(String cell) {
        if (cell == null) return null;
        String joined = Arrays.stream(cell.split("[\\r\\n|]+"))
                .map(v -> v.replaceFirst("^\\*", "").trim())
                .filter(v -> !v.isEmpty())
                .distinct()
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    static String category(String cell) {
        String m = multi(cell);
        if (m == null) return null;
        return Arrays.stream(m.split(", "))
                .map(code -> CATEGORY_LABELS.getOrDefault(code.toUpperCase(Locale.ROOT), code))
                .collect(Collectors.joining(", "));
    }
}
