package com.econet.leads.integration;

import com.econet.leads.integration.support.CsvSupport;
import com.econet.leads.integration.support.ImportConfig;
import com.econet.leads.integration.support.ImportValues;
import com.econet.leads.integration.support.PostalCodes;
import com.econet.leads.integration.support.TextMatcher;
import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.opencsv.CSVReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Registre des entreprises du Québec (REQ) open-data ZIP: six CSV files keyed by NEQ
 * (Entreprise, Etablissements, Nom, FusionScissions, ContinuationsTransformations, DomaineValeur),
 * comma-separated, UTF-8 (per the dataset page and guide IN-537).
 *
 * <h2>Why and how it streams</h2>
 * The archive is ~225 MB compressed and expands to several GB of CSV covering every enterprise in
 * Quebec. It is read from a file on disk with {@link ZipFile} (random access to entries, nothing
 * extracted) and every entry is parsed row by row with a streaming CSV reader. Nothing proportional
 * to the file size is ever held in memory; only the <em>filtered</em> result is:
 * <ol>
 *   <li>DomaineValeur: code -> label map for the employee ranges (a few hundred rows);</li>
 *   <li>Nom (only if a sector has nameKeywords, e.g. "syndicat de copropriété"): set of NEQs whose
 *       current name matches;</li>
 *   <li>Etablissements: keeps establishments located in a configured city AND in a configured
 *       sector (activity code, activity description keywords, establishment name keywords, or
 *       NEQ from step 2). This is the step that cuts millions of rows down to the candidates;</li>
 *   <li>Entreprise: for candidate NEQs only, drops enterprises whose status is not active
 *       (COD_STAT_IMMAT not in activeStatusCodes, default "IM" = immatriculée) and reads the
 *       employee range;</li>
 *   <li>Nom: for remaining candidates, picks the current name (STAT_NOM "V");</li>
 *   <li>emits one lead per enterprise (externalId = NEQ) to the job runner, one at a time.</li>
 * </ol>
 * Memory is therefore O(number of matching establishments) — thousands to a few tens of thousands
 * small objects for the Montréal area — independent of the archive size.
 * {@link Stats} exposes the counters so a test can assert this.
 */
@Component
@Slf4j
public class QuebecBusinessRegisterImporter {

    public static final String IMPORTER = "QUEBEC_BUSINESS_REGISTER";

    static final Map<String, String> DEFAULT_FILES = Map.of(
            "enterprises", "Entreprise",
            "establishments", "Etablissement",
            "names", "Nom",
            "domainValues", "DomaineValeur");

    static final Map<String, String> DEFAULT_ESTABLISHMENT_COLUMNS = linked(
            "neq", "NEQ", "principal", "IND_ETAB_PRINC", "line1", "LIGN1_ADR", "line2", "LIGN2_ADR",
            "line3", "LIGN3_ADR", "line4", "LIGN4_ADR", "activityCode", "COD_ACT_ECON",
            "activityDescription", "DESC_ACT_ECON_ETAB", "activityCode2", "COD_ACT_ECON2",
            "activityDescription2", "DESC_ACT_ECON_ETAB2", "name", "NOM_ETAB");
    static final List<String> REQUIRED_ESTABLISHMENT = List.of("neq", "line1", "line2", "activityCode", "activityDescription");

    static final Map<String, String> DEFAULT_ENTERPRISE_COLUMNS = linked(
            "neq", "NEQ", "status", "COD_STAT_IMMAT", "employees", "COD_INTVAL_EMPLO_QUE",
            "activityCode", "COD_ACT_ECON_CAE", "activityDescription", "DESC_ACT_ECON_ASSUJ");
    static final List<String> REQUIRED_ENTERPRISE = List.of("neq", "status");

    static final Map<String, String> DEFAULT_NAME_COLUMNS = linked(
            "neq", "NEQ", "name", "NOM_ASSUJ", "status", "STAT_NOM", "type", "TYP_NOM_ASSUJ", "endDate", "DAT_FIN_NOM_ASSUJ");
    static final List<String> REQUIRED_NAME = List.of("neq", "name");

    static final Map<String, String> DEFAULT_DOMAIN_COLUMNS = linked(
            "type", "TYP_DOM_VAL", "code", "COD_DOM_VAL", "label", "VAL_DOM_FRAN");

    /** Counters for the job log and for the streaming test. */
    public static final class Stats {
        public long establishmentRows;
        public long enterpriseRows;
        public long nameRows;
        public long nameMatchedNeqs;
        public long candidatesAfterEstablishments;
        public long candidatesAfterStatus;
        public long emitted;
    }

    record Sector(String label, Set<String> codes, TextMatcher keywords, TextMatcher nameKeywords) {
    }

    record Settings(String downloadUrl, String ckanBaseUrl, String resourceId, Charset charset, char separator,
                    Map<String, String> files, Map<String, String> estCols, Map<String, String> entCols,
                    Map<String, String> nameCols, Map<String, String> domainCols,
                    Set<String> activeStatuses, Set<String> currentNameStatuses, List<String> preferredNameTypes,
                    String employeeDomainType, Map<String, String> cityByNormalizedName, List<Sector> sectors) {
    }

    /** Candidate establishment kept after pass 3 (one per NEQ). */
    static final class Candidate {
        final String neq;
        String street;
        String city;
        String postalCode;
        String sector;
        String activity;
        String establishmentName;
        boolean principal;
        String employees;
        String name;
        int nameRank = Integer.MAX_VALUE;

        Candidate(String neq) {
            this.neq = neq;
        }
    }

    public Settings parse(DataSource source) {
        ImportConfig c = new ImportConfig(source.getSourceName(), source.getConfig());
        Map<String, String> files = new LinkedHashMap<>(DEFAULT_FILES);
        c.section("files").raw().forEach((k, v) -> files.put(k, String.valueOf(v)));
        ImportConfig columns = c.section("columns");

        Map<String, String> cities = new LinkedHashMap<>();
        for (String city : c.strings("cities", List.of("Montréal", "Laval", "Longueuil", "Brossard"))) {
            cities.put(TextMatcher.normalize(city), city);
        }
        c.stringLists("cityAliases").forEach((city, aliases) -> {
            for (String alias : aliases) {
                cities.put(TextMatcher.normalize(alias), city);
            }
        });

        List<Sector> sectors = new ArrayList<>();
        for (ImportConfig s : c.sections("sectors")) {
            Set<String> codes = new HashSet<>(s.strings("codes", List.of()));
            sectors.add(new Sector(s.requireString("label"), codes,
                    new TextMatcher(s.strings("keywords", List.of())),
                    new TextMatcher(s.strings("nameKeywords", List.of()))));
        }
        if (sectors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration for " + source.getSourceName() + ": 'sectors' is empty");
        }
        String sep = c.string("separator", ",");
        return new Settings(
                c.string("downloadUrl", null),
                c.string("ckanBaseUrl", "https://www.donneesquebec.ca/recherche/api/3/action/"),
                c.string("resourceId", null),
                Charset.forName(c.string("charset", "UTF-8")),
                sep.charAt(0),
                files,
                merged(DEFAULT_ESTABLISHMENT_COLUMNS, columns.section("establishments")),
                merged(DEFAULT_ENTERPRISE_COLUMNS, columns.section("enterprises")),
                merged(DEFAULT_NAME_COLUMNS, columns.section("names")),
                merged(DEFAULT_DOMAIN_COLUMNS, columns.section("domainValues")),
                new HashSet<>(c.strings("activeStatusCodes", List.of("IM"))),
                new HashSet<>(c.strings("currentNameStatuses", List.of("V"))),
                c.strings("preferredNameTypes", List.of("N", "M", "A")),
                c.string("employeeDomainType", "INTVAL_EMPLO_QUE"),
                cities,
                sectors);
    }

    /**
     * Streams the leads of the archive at {@code zipPath}. {@code stats} is filled as the passes run.
     */
    public RecordStream<Business> stream(Path zipPath, DataSource source, Settings s, ImportReport report, Stats stats) {
        return sink -> run(zipPath, source, s, report, stats, sink);
    }

    void run(Path zipPath, DataSource source, Settings s, ImportReport report, Stats stats, Predicate<Business> sink) throws IOException {
        try (ZipFile zip = openZip(zipPath)) {
            Map<String, ZipEntry> entries = locateEntries(zip, s.files());

            Map<String, String> employeeLabels = readEmployeeLabels(zip, entries.get("domainValues"), s);

            boolean nameKeywords = s.sectors().stream().anyMatch(x -> !x.nameKeywords().isEmpty());
            Map<String, String> nameMatches = nameKeywords ? matchNames(zip, entries.get("names"), s, stats) : Map.of();

            Map<String, Candidate> candidates = scanEstablishments(zip, entries.get("establishments"), s, nameMatches, stats);
            stats.candidatesAfterEstablishments = candidates.size();

            filterEnterprises(zip, entries.get("enterprises"), s, candidates, employeeLabels, stats);
            stats.candidatesAfterStatus = candidates.size();

            readNames(zip, entries.get("names"), s, candidates, stats);

            report.note(String.format(Locale.ROOT,
                    "%d establishment rows read; %d matched city+sector; %d active enterprises; %d enterprise rows, %d name rows read",
                    stats.establishmentRows, stats.candidatesAfterEstablishments, stats.candidatesAfterStatus,
                    stats.enterpriseRows, stats.nameRows));
            if (stats.establishmentRows == 0) {
                throw new IllegalStateException("The establishments file of the register archive has no rows");
            }

            for (Candidate cand : candidates.values()) {
                Business b = toBusiness(cand, source);
                if (b == null) continue;
                stats.emitted++;
                if (!sink.test(b)) return;
            }
        }
    }

    private static ZipFile openZip(Path zipPath) throws IOException {
        try {
            return new ZipFile(zipPath.toFile());
        } catch (java.util.zip.ZipException e) {
            throw new IOException("The register file is not a valid ZIP archive (" + e.getMessage() + ")", e);
        }
    }

    /** Finds each configured file by base name (case/accent-insensitive; exact name or name prefix, shortest wins). */
    static Map<String, ZipEntry> locateEntries(ZipFile zip, Map<String, String> files) {
        List<ZipEntry> csvs = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                csvs.add(e);
            }
        }
        Map<String, ZipEntry> found = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String wanted = TextMatcher.normalize(f.getValue());
            ZipEntry best = null;
            int bestLength = Integer.MAX_VALUE;
            for (ZipEntry e : csvs) {
                String base = e.getName().replaceAll("^.*/", "").replaceAll("(?i)\\.csv$", "");
                String n = TextMatcher.normalize(base);
                if ((n.equals(wanted) || n.startsWith(wanted)) && base.length() < bestLength) {
                    best = e;
                    bestLength = base.length();
                }
            }
            if (best == null) {
                missing.add(f.getValue() + ".csv (" + f.getKey() + ")");
            } else {
                found.put(f.getKey(), best);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Register archive: missing expected file(s) " + missing + ". Files found: "
                    + csvs.stream().map(ZipEntry::getName).sorted().toList()
                    + ". Fix 'files' in the data source config if the archive layout changed.");
        }
        return found;
    }

    private Map<String, String> readEmployeeLabels(ZipFile zip, ZipEntry entry, Settings s) throws IOException {
        Map<String, String> labels = new HashMap<>();
        try (InputStream in = zip.getInputStream(entry); CSVReader reader = CsvSupport.open(in, s.charset(), s.separator())) {
            Map<String, Integer> cols = CsvSupport.resolve(entry.getName(), CsvSupport.readNext(reader), s.domainCols(),
                    List.of("type", "code", "label"));
            String[] row;
            while ((row = CsvSupport.readNext(reader)) != null) {
                if (s.employeeDomainType().equalsIgnoreCase(String.valueOf(CsvSupport.get(row, cols, "type")))) {
                    String code = CsvSupport.get(row, cols, "code");
                    String label = CsvSupport.get(row, cols, "label");
                    if (code != null && label != null) labels.put(code, label);
                }
            }
        }
        return labels;
    }

    /** NEQ -> sector label for current names matching a sector's nameKeywords. */
    private Map<String, String> matchNames(ZipFile zip, ZipEntry entry, Settings s, Stats stats) throws IOException {
        Map<String, String> matches = new HashMap<>();
        try (InputStream in = zip.getInputStream(entry); CSVReader reader = CsvSupport.open(in, s.charset(), s.separator())) {
            Map<String, Integer> cols = CsvSupport.resolve(entry.getName(), CsvSupport.readNext(reader), s.nameCols(), REQUIRED_NAME);
            String[] row;
            while ((row = CsvSupport.readNext(reader)) != null) {
                String status = CsvSupport.get(row, cols, "status");
                if (status != null && !s.currentNameStatuses().contains(status)) continue;
                String name = CsvSupport.get(row, cols, "name");
                for (Sector sector : s.sectors()) {
                    if (!sector.nameKeywords().isEmpty() && sector.nameKeywords().matchesAny(name)) {
                        String neq = CsvSupport.get(row, cols, "neq");
                        if (neq != null) matches.putIfAbsent(neq, sector.label());
                        break;
                    }
                }
            }
        }
        stats.nameMatchedNeqs = matches.size();
        return matches;
    }

    private Map<String, Candidate> scanEstablishments(ZipFile zip, ZipEntry entry, Settings s, Map<String, String> nameMatches,
                                                      Stats stats) throws IOException {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        try (InputStream in = zip.getInputStream(entry); CSVReader reader = CsvSupport.open(in, s.charset(), s.separator())) {
            Map<String, Integer> cols = CsvSupport.resolve(entry.getName(), CsvSupport.readNext(reader), s.estCols(), REQUIRED_ESTABLISHMENT);
            String[] row;
            while ((row = CsvSupport.readNext(reader)) != null) {
                stats.establishmentRows++;
                String neq = CsvSupport.get(row, cols, "neq");
                if (neq == null) continue;
                Address address = address(row, cols, s);
                if (address == null) continue;

                String code = CsvSupport.get(row, cols, "activityCode");
                String code2 = CsvSupport.get(row, cols, "activityCode2");
                String desc = CsvSupport.get(row, cols, "activityDescription");
                String desc2 = CsvSupport.get(row, cols, "activityDescription2");
                String estName = CsvSupport.get(row, cols, "name");
                String sector = sectorOf(s, code, code2, desc, desc2, estName);
                if (sector == null) {
                    sector = nameMatches.get(neq);
                }
                if (sector == null) continue;

                boolean principal = isYes(CsvSupport.get(row, cols, "principal"));
                Candidate existing = candidates.get(neq);
                if (existing != null && (existing.principal || !principal)) {
                    continue; // keep one establishment per enterprise, the principal one when known
                }
                Candidate cand = new Candidate(neq);
                cand.street = address.street;
                cand.city = address.city;
                cand.postalCode = address.postalCode;
                cand.sector = sector;
                cand.activity = desc != null ? desc : desc2;
                cand.establishmentName = estName;
                cand.principal = principal;
                candidates.put(neq, cand);
            }
        }
        return candidates;
    }

    private void filterEnterprises(ZipFile zip, ZipEntry entry, Settings s, Map<String, Candidate> candidates,
                                   Map<String, String> employeeLabels, Stats stats) throws IOException {
        Set<String> active = new HashSet<>();
        try (InputStream in = zip.getInputStream(entry); CSVReader reader = CsvSupport.open(in, s.charset(), s.separator())) {
            Map<String, Integer> cols = CsvSupport.resolve(entry.getName(), CsvSupport.readNext(reader), s.entCols(), REQUIRED_ENTERPRISE);
            String[] row;
            while ((row = CsvSupport.readNext(reader)) != null) {
                stats.enterpriseRows++;
                String neq = CsvSupport.get(row, cols, "neq");
                Candidate cand = neq == null ? null : candidates.get(neq);
                if (cand == null) continue;
                if (!s.activeStatuses().contains(CsvSupport.get(row, cols, "status"))) continue;
                active.add(neq);
                String emp = CsvSupport.get(row, cols, "employees");
                if (emp != null) cand.employees = employeeLabels.getOrDefault(emp, emp);
                if (cand.activity == null) cand.activity = CsvSupport.get(row, cols, "activityDescription");
            }
        }
        candidates.keySet().retainAll(active);
    }

    private void readNames(ZipFile zip, ZipEntry entry, Settings s, Map<String, Candidate> candidates, Stats stats) throws IOException {
        try (InputStream in = zip.getInputStream(entry); CSVReader reader = CsvSupport.open(in, s.charset(), s.separator())) {
            Map<String, Integer> cols = CsvSupport.resolve(entry.getName(), CsvSupport.readNext(reader), s.nameCols(), REQUIRED_NAME);
            String[] row;
            while ((row = CsvSupport.readNext(reader)) != null) {
                stats.nameRows++;
                String neq = CsvSupport.get(row, cols, "neq");
                Candidate cand = neq == null ? null : candidates.get(neq);
                if (cand == null) continue;
                String status = CsvSupport.get(row, cols, "status");
                if (status != null && !s.currentNameStatuses().contains(status)) continue;
                if (CsvSupport.get(row, cols, "endDate") != null) continue;
                String name = CsvSupport.get(row, cols, "name");
                if (name == null) continue;
                int rank = s.preferredNameTypes().indexOf(String.valueOf(CsvSupport.get(row, cols, "type")));
                if (rank < 0) rank = s.preferredNameTypes().size();
                if (rank < cand.nameRank) {
                    cand.nameRank = rank;
                    cand.name = name;
                }
            }
        }
    }

    private String sectorOf(Settings s, String code, String code2, String desc, String desc2, String estName) {
        for (Sector sector : s.sectors()) {
            if ((code != null && sector.codes().contains(code)) || (code2 != null && sector.codes().contains(code2))
                    || sector.keywords().matchesAny(desc, desc2)
                    || (!sector.nameKeywords().isEmpty() && sector.nameKeywords().matchesAny(estName))) {
                return sector.label();
            }
        }
        return null;
    }

    record Address(String street, String city, String postalCode) {
    }

    /**
     * Establishment address lines: LIGN1 = street; the city line ("Montréal (Québec)") is LIGN2 or,
     * when there is a unit line, LIGN3; LIGN4 (or any line) holds the postal code. Returns null when
     * no line names a configured city.
     */
    static Address address(String[] row, Map<String, Integer> cols, Settings s) {
        String l1 = CsvSupport.get(row, cols, "line1");
        if (l1 == null) return null;
        String[] lines = {CsvSupport.get(row, cols, "line2"), CsvSupport.get(row, cols, "line3")};
        String city = null;
        int cityLine = -1;
        for (int i = 0; i < lines.length && city == null; i++) {
            if (lines[i] == null) continue;
            String candidate = lines[i].replaceAll("\\(.*?\\)", "");
            // a city line may also carry the postal code: "Montréal (Québec) H2X 1Y4"
            candidate = PostalCodes.strip(candidate);
            city = s.cityByNormalizedName().get(TextMatcher.normalize(candidate));
            if (city != null) cityLine = i;
        }
        if (city == null) return null;
        String street = l1;
        if (cityLine == 1 && lines[0] != null) {
            street = l1 + ", " + lines[0];
        }
        String postal = null;
        for (String l : new String[]{CsvSupport.get(row, cols, "line4"), lines[0], lines[1]}) {
            postal = PostalCodes.find(l);
            if (postal != null) break;
        }
        return new Address(street, city, postal);
    }

    private static boolean isYes(String v) {
        return v != null && (v.equalsIgnoreCase("O") || v.equalsIgnoreCase("Y") || v.equals("1") || v.equalsIgnoreCase("oui"));
    }

    private static Business toBusiness(Candidate c, DataSource source) {
        String name = c.name != null ? c.name : c.establishmentName;
        if (name == null) return null;
        Business b = new Business();
        b.setBusinessName(ImportValues.truncate(name, 255));
        b.setBusinessType(c.sector);
        b.setAddressStreet(ImportValues.truncate(c.street, 255));
        b.setAddressCity(c.city);
        b.setAddressProvince("QC");
        b.setPostalCode(c.postalCode);
        b.setDataSource(source.getSourceName());
        b.setSourceUrl(source.getSourceUrl());
        b.setExternalId(c.neq);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("NEQ", c.neq);
        details.put("Secteur", c.sector);
        if (c.activity != null) details.put("Activité", ImportValues.truncate(c.activity, 150));
        if (c.employees != null) details.put("Employés", c.employees);
        if (c.establishmentName != null && !c.establishmentName.equalsIgnoreCase(name)) {
            details.put("Établissement", ImportValues.truncate(c.establishmentName, 150));
        }
        b.setSourceDetails(details);
        return b;
    }

    private static Map<String, String> merged(Map<String, String> defaults, ImportConfig overrides) {
        Map<String, String> m = new LinkedHashMap<>(defaults);
        overrides.raw().forEach((k, v) -> m.put(k, v == null ? null : v.toString()));
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }
}
