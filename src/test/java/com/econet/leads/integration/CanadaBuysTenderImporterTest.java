package com.econet.leads.integration;

import com.econet.leads.model.DataSource;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * CanadaBuys importer against fixtures/canadabuys-open-tenders.csv (UTF-8 BOM, CRLF, 67 columns).
 * English column names: the target-column list of github.com/anshjindal/tdp-tender-discovery-platform
 * apps/backend/src/main.ts (built from the live file), full long names from
 * github.com/Krish120003/where-are-my-taxes-going, the key-field table and value conventions
 * ("*value" lines, "*SRV", "*Open", closing "2026-07-10T17:00:00") from github.com/leog25/govcon-cli
 * docs/data-sources.md and tests/fixtures/canadabuys_open_sample.csv (verified live 2026-06-23),
 * region values ("Quebec (except NCR)", "National Capital Region (NCR)", "Canada") from
 * github.com/nlledger/nl-ledger and github.com/jiroamato/bidly. The "-fra" twins of "-eng"
 * columns are inferred by symmetry (not verified); they are optional for the importer.
 */
class CanadaBuysTenderImporterTest {

    CanadaBuysTenderImporter importer = new CanadaBuysTenderImporter(mock(com.econet.leads.integration.support.HttpDownloader.class));

    private CanadaBuysTenderImporter.Settings settings() throws Exception {
        return importer.parse(RegisterFixtures.source("CanadaBuys - Appels d'offres", DataSource.SourceType.CSV_DOWNLOAD));
    }

    private List<Tender> read(InputStream in, ImportReport report) throws Exception {
        List<Tender> out = new ArrayList<>();
        importer.read(in, settings(), report, out::add);
        return out;
    }

    @Test
    void keepsCleaningNoticesForQuebecOrNationalDelivery() throws Exception {
        ImportReport report = new ImportReport();
        List<Tender> tenders = read(getClass().getResourceAsStream("/fixtures/canadabuys-open-tenders.csv"), report);

        // dropped: Ontario delivery, no keyword (drones), cancelled
        assertThat(tenders).extracting(Tender::getExternalId).containsExactly("EP123-260001/A", "5X001-26-0042");

        Tender janitorial = tenders.get(0);
        assertThat(janitorial.getSource()).isEqualTo(TenderSource.CANADABUYS);
        assertThat(janitorial.getTitle()).isEqualTo("Services de conciergerie - Complexe Guy-Favreau, Montréal");
        assertThat(janitorial.getBuyer()).isEqualTo("Travaux publics et Services gouvernementaux Canada");
        assertThat(janitorial.getRegion()).isEqualTo("Quebec (except NCR)");
        assertThat(janitorial.getCategory()).isEqualTo("Services");
        assertThat(janitorial.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 0, 0));
        assertThat(janitorial.getClosingAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 14, 0));
        assertThat(janitorial.getUrl()).startsWith("https://achatscanada.canada.ca/fr/");
        assertThat(janitorial.getMatchedKeywords()).contains("conciergerie", "janitorial", "custodial", "entretien ménager", "cleaning");
        assertThat(janitorial.getEstimatedValue()).isNull();

        Tender national = tenders.get(1);
        assertThat(national.getMatchedKeywords()).contains("entretien ménager", "housekeeping"); // "ENTRETIEN MENAGER"
        assertThat(national.getRegion()).isEqualTo("Canada");
        assertThat(national.getCategory()).isEqualTo("Services, Services liés aux biens");

        assertThat(report.asText()).contains("5 notices read, 4 matched keywords, 2 kept");
    }

    @Test
    void failsListingMissingColumns() {
        String csv = "﻿title-titre-eng,referenceNumber-numeroReference\nCleaning,1\n";
        assertThatThrownBy(() -> read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), new ImportReport()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenderClosingDate-appelOffresDateCloture (closingAt)")
                .hasMessageContaining("solicitationNumber-numeroSollicitation (solicitationNumber)")
                .hasMessageContaining("noticeURL-URLavis-eng (urlEn)");
    }

    @Test
    void multiValuedCellsAreFlattened() {
        assertThat(CanadaBuysTenderImporter.multi("*Quebec (except NCR)\n*National Capital Region (NCR)"))
                .isEqualTo("Quebec (except NCR), National Capital Region (NCR)");
        assertThat(CanadaBuysTenderImporter.category("*CNST")).isEqualTo("Construction");
        assertThat(CanadaBuysTenderImporter.multi("")).isNull();
    }
}
