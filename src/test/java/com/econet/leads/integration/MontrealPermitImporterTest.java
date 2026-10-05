package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Montréal permits importer against fixtures/montreal-permits-datastore.json, a CKAN
 * datastore_search response. Its 16 columns are those reported for the 2026-09 permis-construction
 * CSV by people who downloaded it (github.com/orcunkoraliseri/GSSCanada 0_New_Ideas/5thJ_00_Kickoff_Note.md,
 * github.com/Dev-ops10/montreal-permit-oracle mcp-montreal-permits/server.py,
 * github.com/mstlaur1/montreal-score scripts/ingest_permits.py). Note: no cost column.
 */
class MontrealPermitImporterTest {

    static final ZoneId MTL = ZoneId.of("America/Montreal");
    static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 10, 5, 12, 0).atZone(MTL).toInstant(), MTL);

    CkanApiClient ckan;
    MontrealPermitImporter importer;
    DataSource source;
    CkanApiClient.DatastorePage page;

    @BeforeEach
    void setUp() throws Exception {
        ckan = mock(CkanApiClient.class);
        importer = new MontrealPermitImporter(ckan, CLOCK);
        source = new DataSource();
        source.setSourceName("Montréal - Permis de construction");
        source.setSourceUrl("https://donnees.montreal.ca/dataset/permis-construction");
        Map<String, Object> config = new HashMap<>();
        config.put("importer", MontrealPermitImporter.IMPORTER);
        config.put("resourceId", "5232a72d-235a-48eb-ae20-bb9d501300ad");
        config.put("batchSize", 1000);
        source.setConfig(config);

        String body = new String(getClass().getResourceAsStream("/fixtures/montreal-permits-datastore.json").readAllBytes(), StandardCharsets.UTF_8);
        page = new CkanApiClient().parseDatastorePage(body, "5232a72d-235a-48eb-ae20-bb9d501300ad");
    }

    private List<Business> runWith(CkanApiClient.DatastorePage p, ImportReport report) throws Exception {
        when(ckan.fetchDatastorePage(anyString(), eq("5232a72d-235a-48eb-ae20-bb9d501300ad"), anyInt(), anyInt(), eq("date_emission desc")))
                .thenReturn(p);
        List<Business> out = new ArrayList<>();
        importer.stream(source, importer.parse(source), report).forEach(out::add);
        return out;
    }

    @Test
    void keepsRecentLargeConstructionAndTransformationPermits() throws Exception {
        ImportReport report = new ImportReport();
        List<Business> leads = runWith(page, report);

        // kept: office construction, 48-unit construction, office transformation (same address as the first)
        // dropped: single-family renovation (no cost column -> residential fallback), tree felling, permit older than 120 days
        assertThat(leads).extracting(Business::getExternalId).containsExactly("3003456789", "3003456791", "3003456793");
        Business office = leads.get(0);
        assertThat(office.getBusinessName()).isEqualTo("Chantier – 1250 rue Exemple");
        assertThat(office.getBusinessType()).isEqualTo("Chantier");
        assertThat(office.getAddressCity()).isEqualTo("Montréal");
        assertThat(office.getPhone()).isNull();
        assertThat(office.getLatitude()).isEqualByComparingTo("45.5017");
        assertThat(office.getSourceDetails())
                .containsEntry("Travaux", "Construction")
                .containsEntry("Date du permis", "2026-09-28")
                .containsEntry("Arrondissement", "Ville-Marie")
                .doesNotContainKey("Coût estimé");
        assertThat(leads.get(1).getSourceDetails()).containsEntry("Logements", "48");
        assertThat(report.lines()).anyMatch(l -> l.contains("cout_travaux_estimes") && l.contains("not published"));
        assertThat(report.lines()).anyMatch(l -> l.contains("6 rows read") && l.contains("3 kept"));
    }

    @Test
    void appliesMinEstimatedCostWhenTheCostColumnExists() throws Exception {
        List<String> fields = new ArrayList<>(page.fields());
        fields.add("cout_travaux_estimes");
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> r : page.records()) {
            Map<String, Object> copy = new LinkedHashMap<>(r);
            copy.put("cout_travaux_estimes", "3003456789".equals(r.get("no_demande")) ? "4500000" : "20000");
            records.add(copy);
        }
        List<Business> leads = runWith(new CkanApiClient.DatastorePage(fields, records, records.size()), new ImportReport());

        assertThat(leads).extracting(Business::getExternalId).containsExactly("3003456789");
        assertThat(leads.get(0).getSourceDetails()).containsEntry("Coût estimé", "4 500 000 $");
    }

    @Test
    void failsWithTheListOfMissingColumns() {
        List<String> fields = page.fields().stream().filter(f -> !f.equals("emplacement") && !f.equals("date_emission")).toList();
        assertThatThrownBy(() -> runWith(new CkanApiClient.DatastorePage(fields, page.records(), 1), new ImportReport()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("date_emission (issueDate)")
                .hasMessageContaining("emplacement (address)")
                .hasMessageContaining("Columns found");
    }

    @Test
    void failsWhenTheDatastoreIsEmpty() {
        assertThatThrownBy(() -> runWith(new CkanApiClient.DatastorePage(page.fields(), List.of(), 0), new ImportReport()))
                .hasMessageContaining("returned no rows");
    }

    @Test
    void invalidConfigIsRejectedUpFront() {
        source.getConfig().remove("resourceId");
        assertThatThrownBy(() -> importer.parse(source)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceId");
    }
}
