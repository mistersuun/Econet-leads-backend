package com.econet.leads.integration;

import com.econet.leads.integration.support.HttpDownloader;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SEAO importer against fixtures/seao-mensuel-sample.json (structure and provenance in its
 * "_comment": OCDS 1.1 release package; SEAO conventions from github.com/j-kohl/gov-budget-audit
 * src/govbudget/parsers/ocds.py, verified on mensuel_20260601_20260630.json, and
 * github.com/marcdoesdat/seao scripts/build-index.js).
 */
class SeaoTenderImporterTest {

    CkanApiClient ckan = mock(CkanApiClient.class);
    SeaoTenderImporter importer = new SeaoTenderImporter(mock(HttpDownloader.class), ckan);

    private SeaoTenderImporter.Settings settings() throws Exception {
        return importer.parse(RegisterFixtures.source("SEAO - Avis et contrats", DataSource.SourceType.CKAN_API));
    }

    @Test
    void mapsTendersAndAwardedContracts() throws Exception {
        SeaoTenderImporter.Counts counts = new SeaoTenderImporter.Counts();
        List<Tender> out = new ArrayList<>();
        importer.read(getClass().getResourceAsStream("/fixtures/seao-mensuel-sample.json"), settings(), counts, out::add);

        // road works: no keyword; cancelled metro cleaning: dropped; the older release of 20170001 is
        // emitted too (the upsert ignores it, see TendersApiIntegrationTest)
        assertThat(out).extracting(Tender::getExternalId)
                .containsExactly("ocds-ec9k95-20170001", "ocds-ec9k95-20160002", "ocds-ec9k95-20170001");

        Tender schools = out.get(0);
        assertThat(schools.getSource()).isEqualTo(TenderSource.SEAO);
        assertThat(schools.getTitle()).isEqualTo("Services d'entretien ménager - écoles secondaires secteur Est");
        assertThat(schools.getBuyer()).isEqualTo("Centre de services scolaire de Montréal");
        assertThat(schools.getRegion()).isEqualTo("Montréal (06)");
        assertThat(schools.getCategory()).isEqualTo("S12 - Services de nettoyage et d'entretien");
        assertThat(schools.getClosingAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 14, 0));
        assertThat(schools.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(schools.getEstimatedValue()).isEqualByComparingTo("1250000");
        assertThat(schools.getUrl()).isEqualTo("https://seao.gouv.qc.ca/avis-resultat-recherche/consulter?ItemId=0f0e0d0c-0b0a-4908-8706-050403020100");
        assertThat(schools.getNoticeType()).isEqualTo("TENDER");
        assertThat(schools.getMatchedKeywords()).contains("entretien ménager", "nettoyage");

        Tender award = out.get(1);
        assertThat(award.getNoticeType()).isEqualTo("AWARD");
        assertThat(award.getAwardedTo()).isEqualTo("Conciergerie Exemple inc.");
        assertThat(award.getContractEndAt()).isEqualTo(LocalDateTime.of(2028, 10, 31, 0, 0));
        // CRM convention: awarded contracts carry "Contrat octroyé" and closingAt = contract end
        assertThat(award.getClosingAt()).isEqualTo(LocalDateTime.of(2028, 10, 31, 0, 0));
        assertThat(award.getCategory()).isEqualTo("Contrat octroyé – S12 - Services de nettoyage et d'entretien");
        assertThat(award.getEstimatedValue()).isEqualByComparingTo("840000");
        assertThat(award.getUrl()).isEqualTo("https://seao.gouv.qc.ca/avis-du-jour"); // no document link in that release

        assertThat(counts.releases).isEqualTo(5);
        assertThat(counts.awards).isEqualTo(1);
    }

    @Test
    void rejectsFilesThatAreNotReleasePackages() {
        byte[] json = "{\"records\": []}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream(json), settings(), new SeaoTenderImporter.Counts(), t -> true))
                .hasMessageContaining("no 'releases' array");
    }

    @Test
    void discoversTheLatestMonthlyAndWeeklyFilesOldestFirst() throws Exception {
        String pkg = """
                {"resources": [
                  {"name": "mensuel_20260701_20260731.json", "format": "JSON", "url": "https://x/mensuel_20260701_20260731.json"},
                  {"name": "mensuel_20260801_20260831.json", "format": "JSON", "url": "https://x/mensuel_20260801_20260831.json"},
                  {"name": "mensuel_20260601_20260630.json", "format": "JSON", "url": "https://x/mensuel_20260601_20260630.json"},
                  {"name": "hebdo_20260921_20260927.json", "format": "JSON", "url": "https://x/hebdo_20260921_20260927.json"},
                  {"name": "Spécifications JSON", "format": "PDF", "url": "https://x/spec.pdf"}
                ]}""";
        when(ckan.action(anyString(), eq("package_show"), any(Map.class))).thenReturn(new ObjectMapper().readTree(pkg));
        List<String> urls = importer.discover(settings(), new ImportReport());
        assertThat(urls).containsExactly(
                "https://x/mensuel_20260701_20260731.json",
                "https://x/mensuel_20260801_20260831.json",
                "https://x/hebdo_20260921_20260927.json");
    }
}
