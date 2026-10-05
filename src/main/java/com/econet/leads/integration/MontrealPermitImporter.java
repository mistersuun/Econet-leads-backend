package com.econet.leads.integration;

import com.econet.leads.integration.support.ImportConfig;
import com.econet.leads.integration.support.ImportValues;
import com.econet.leads.integration.support.TextMatcher;
import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Montréal construction/transformation permits (dataset "permis-construction" on
 * donnees.montreal.ca, CKAN datastore). Each recent, large-enough permit becomes a "Chantier" lead
 * at the permit address: post-construction / renovation cleaning prospects.
 *
 * <p>Format (verified 2026-09 by third parties who downloaded the file, see report): 16 columns
 * no_demande, id_permis, date_debut, date_emission, emplacement, arrondissement,
 * code_type_base_demande, description_type_demande, description_type_batiment,
 * description_categorie_batiment, nature_travaux, nb_logements, longitude, latitude, loc_x, loc_y.
 * The dataset currently publishes NO estimated-cost column: when the configured cost column is
 * absent the minEstimatedCost filter cannot apply, and the importer falls back to "construction
 * with >= fallbackMinDwellingUnits units, or any non-residential building" and says so in the
 * job log.
 *
 * <p>Paging: datastore_search sorted by issue date descending, stopping at the first page older
 * than lookbackDays, so only the recent tail of the 560k-row table is read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MontrealPermitImporter {

    public static final String IMPORTER = "MONTREAL_PERMITS";

    static final Map<String, String> DEFAULT_FIELDS = Map.ofEntries(
            Map.entry("permitNumber", "no_demande"),
            Map.entry("permitId", "id_permis"),
            Map.entry("issueDate", "date_emission"),
            Map.entry("address", "emplacement"),
            Map.entry("borough", "arrondissement"),
            Map.entry("permitTypeCode", "code_type_base_demande"),
            Map.entry("permitType", "description_type_demande"),
            Map.entry("buildingType", "description_type_batiment"),
            Map.entry("buildingCategory", "description_categorie_batiment"),
            Map.entry("workDescription", "nature_travaux"),
            Map.entry("dwellingUnits", "nb_logements"),
            Map.entry("estimatedCost", "cout_travaux_estimes"),
            Map.entry("latitude", "latitude"),
            Map.entry("longitude", "longitude"));
    static final List<String> DEFAULT_REQUIRED = List.of("permitNumber", "issueDate", "address", "permitType");

    private final CkanApiClient ckanApiClient;
    private final Clock clock;

    /** Parsed settings; parsed eagerly so a broken config is a 400 at import start. */
    record Settings(String baseUrl, String resourceId, int batchSize, int lookbackDays, BigDecimal minEstimatedCost,
                    TextMatcher permitTypes, TextMatcher residential, int fallbackMinDwellingUnits,
                    String businessType, String city, String namePrefix, Map<String, String> fields,
                    List<String> required, int maxPages) {
    }

    public Settings parse(DataSource source) {
        ImportConfig c = new ImportConfig(source.getSourceName(), source.getConfig());
        Map<String, String> fields = new LinkedHashMap<>(DEFAULT_FIELDS);
        ImportConfig mapping = c.section("fieldMapping");
        mapping.raw().forEach((k, v) -> fields.put(k, v == null ? null : v.toString()));
        Double minCost = c.decimal("minEstimatedCost", 50_000d);
        return new Settings(
                c.string("ckanBaseUrl", "https://donnees.montreal.ca/api/3/action/"),
                c.requireString("resourceId"),
                Math.max(10, Math.min(c.integer("batchSize", 1000), 32_000)),
                Math.max(1, c.integer("lookbackDays", 120)),
                minCost == null ? null : BigDecimal.valueOf(minCost),
                new TextMatcher(c.strings("permitTypeKeywords", List.of("construction", "transformation"))),
                new TextMatcher(c.strings("residentialKeywords",
                        List.of("residentiel", "habitation", "logement", "unifamilial", "duplex", "triplex", "condo"))),
                c.integer("fallbackMinDwellingUnits", 4),
                c.string("businessType", "Chantier"),
                c.string("city", "Montréal"),
                c.string("namePrefix", "Chantier – "),
                fields,
                c.strings("requiredFields", DEFAULT_REQUIRED),
                c.integer("maxPages", 200));
    }

    public RecordStream<Business> stream(DataSource source, Settings s, ImportReport report) {
        return sink -> run(source, s, report, sink);
    }

    void run(DataSource source, Settings s, ImportReport report, Predicate<Business> sink) {
        LocalDate cutoff = LocalDate.now(clock).minusDays(s.lookbackDays());
        String sort = s.fields().get("issueDate") + " desc";
        int offset = 0;
        long read = 0, recent = 0, kept = 0;
        boolean costAvailable = false;

        for (int page = 0; page < s.maxPages(); page++) {
            CkanApiClient.DatastorePage p = ckanApiClient.fetchDatastorePage(s.baseUrl(), s.resourceId(), s.batchSize(), offset, sort);
            if (page == 0) {
                costAvailable = checkColumns(s, p.fields(), report);
                if (p.records().isEmpty()) {
                    throw new IllegalStateException("The permits datastore " + s.resourceId() + " returned no rows");
                }
            }
            boolean reachedCutoff = false;
            for (Map<String, Object> r : p.records()) {
                read++;
                LocalDateTime issued = ImportValues.dateTime(str(r, s.fields().get("issueDate")));
                if (issued == null) continue;
                if (issued.toLocalDate().isBefore(cutoff)) {
                    reachedCutoff = true;
                    continue;
                }
                recent++;
                Business b = map(r, s, issued, costAvailable, source);
                if (b != null) {
                    kept++;
                    if (!sink.test(b)) return;
                }
            }
            if (reachedCutoff || p.records().size() < s.batchSize()) {
                break;
            }
            offset += s.batchSize();
        }
        report.note(String.format("%d rows read, %d issued since %s, %d kept as leads", read, recent, cutoff, kept));
    }

    /** @return whether the estimated-cost column exists */
    boolean checkColumns(Settings s, List<String> columns, ImportReport report) {
        List<String> missing = new ArrayList<>();
        for (String logical : s.required()) {
            String col = s.fields().get(logical);
            if (col == null || !columns.contains(col)) {
                missing.add(col + " (" + logical + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Montréal permits: missing expected column(s) " + missing
                    + ". Columns found: " + columns + ". Fix fieldMapping in the data source config.");
        }
        String costColumn = s.fields().get("estimatedCost");
        boolean available = costColumn != null && columns.contains(costColumn);
        if (!available && s.minEstimatedCost() != null) {
            report.note("Column '" + costColumn + "' (estimated cost) is not published: minEstimatedCost not applied; "
                    + "kept permits are construction with >= " + s.fallbackMinDwellingUnits()
                    + " dwelling units or on non-residential buildings");
        }
        return available;
    }

    Business map(Map<String, Object> r, Settings s, LocalDateTime issued, boolean costAvailable, DataSource source) {
        Map<String, String> f = s.fields();
        String type = str(r, f.get("permitType"));
        String typeCode = str(r, f.get("permitTypeCode"));
        if (!s.permitTypes().matchesAny(type, typeCode)) {
            return null;
        }
        String address = str(r, f.get("address"));
        String number = str(r, f.get("permitNumber"));
        if (address == null || number == null) {
            return null;
        }
        String buildingType = str(r, f.get("buildingType"));
        String buildingCategory = str(r, f.get("buildingCategory"));
        BigDecimal units = ImportValues.amount(str(r, f.get("dwellingUnits")));
        BigDecimal cost = costAvailable ? ImportValues.amount(str(r, f.get("estimatedCost"))) : null;

        if (costAvailable && s.minEstimatedCost() != null) {
            if (cost == null || cost.compareTo(s.minEstimatedCost()) < 0) return null;
        } else if (s.minEstimatedCost() != null) {
            boolean residential = s.residential().matchesAny(buildingType, buildingCategory);
            boolean bigResidential = units != null && units.intValue() >= s.fallbackMinDwellingUnits();
            if (residential && !bigResidential) return null;
        }

        Business b = new Business();
        b.setBusinessName(ImportValues.truncate(s.namePrefix() + address, 255));
        b.setBusinessType(s.businessType());
        b.setAddressStreet(ImportValues.truncate(address, 255));
        b.setAddressCity(s.city());
        b.setAddressProvince("QC");
        b.setLatitude(ImportValues.coordinate(r.get(f.get("latitude"))));
        b.setLongitude(ImportValues.coordinate(r.get(f.get("longitude"))));
        if (b.getLatitude() == null || b.getLongitude() == null) {
            b.setLatitude(null);
            b.setLongitude(null);
        }
        b.setDataSource(source.getSourceName());
        b.setSourceUrl(source.getSourceUrl());
        b.setExternalId(number);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Travaux", type);
        String work = str(r, f.get("workDescription"));
        if (work != null) details.put("Description", ImportValues.truncate(work, 200));
        if (cost != null) details.put("Coût estimé", ImportValues.money(cost));
        details.put("Date du permis", issued.toLocalDate().toString());
        details.put("No de demande", number);
        String borough = str(r, f.get("borough"));
        if (borough != null) details.put("Arrondissement", borough);
        if (buildingType != null) details.put("Bâtiment", buildingType);
        if (units != null && units.signum() > 0) details.put("Logements", units.stripTrailingZeros().toPlainString());
        b.setSourceDetails(details);
        return b;
    }

    private static String str(Map<String, Object> r, String column) {
        if (column == null) return null;
        Object v = r.get(column);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
