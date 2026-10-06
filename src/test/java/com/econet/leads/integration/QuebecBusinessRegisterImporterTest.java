package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Register importer against a ZIP of the six fixture CSVs (see {@link RegisterFixtures} for provenance). */
class QuebecBusinessRegisterImporterTest {

    @TempDir
    Path tmp;

    QuebecBusinessRegisterImporter importer = new QuebecBusinessRegisterImporter();
    DataSource source;

    @BeforeEach
    void setUp() throws Exception {
        source = RegisterFixtures.source("Registre des entreprises du Québec", DataSource.SourceType.BULK_FILE);
        source.setSourceUrl("https://www.donneesquebec.ca/recherche/dataset/registre-des-entreprises");
    }

    private List<Business> run(Path zip, QuebecBusinessRegisterImporter.Stats stats, ImportReport report) throws Exception {
        List<Business> out = new ArrayList<>();
        importer.stream(zip, source, importer.parse(source), report, stats).forEach(out::add);
        return out;
    }

    @Test
    void joinsTheFilesByNeqAndKeepsActiveEnterprisesInTargetCitiesAndSectors() throws Exception {
        QuebecBusinessRegisterImporter.Stats stats = new QuebecBusinessRegisterImporter.Stats();
        ImportReport report = new ImportReport();
        List<Business> leads = run(RegisterFixtures.zipFixtures(tmp), stats, report);

        assertThat(leads).extracting(Business::getExternalId)
                .containsExactlyInAnyOrder("1160000001", "1160000002", "1160000005", "1160000007");

        Business dentist = byNeq(leads, "1160000001");
        assertThat(dentist.getBusinessName()).isEqualTo("Clinique Dentaire Exemple inc."); // current legal name, not the ended one
        assertThat(dentist.getBusinessType()).isEqualTo("Dentistes");
        assertThat(dentist.getAddressStreet()).isEqualTo("1000, rue Exemple"); // quoted comma kept in one column
        assertThat(dentist.getAddressCity()).isEqualTo("Montréal");
        assertThat(dentist.getPostalCode()).isEqualTo("H3A 1B2");
        assertThat(dentist.getPhone()).isNull();
        assertThat(dentist.getSourceDetails())
                .containsEntry("NEQ", "1160000001")
                .containsEntry("Secteur", "Dentistes")
                .containsEntry("Employés", "De 6 à 10");

        Business lawyers = byNeq(leads, "1160000002");
        assertThat(lawyers.getAddressCity()).isEqualTo("Laval"); // the Québec City establishment is ignored
        assertThat(lawyers.getBusinessType()).isEqualTo("Bureaux professionnels");

        Business condo = byNeq(leads, "1160000005");
        assertThat(condo.getBusinessType()).isEqualTo("Syndicats de copropriété"); // matched on the name only
        assertThat(condo.getAddressCity()).isEqualTo("Brossard");

        Business daycare = byNeq(leads, "1160000007");
        assertThat(daycare.getAddressStreet()).isEqualTo("200 boul. Exemple, bureau 300"); // unit line, city on line 3
        assertThat(daycare.getAddressCity()).isEqualTo("Longueuil");

        assertThat(stats.establishmentRows).isEqualTo(8);
        assertThat(stats.candidatesAfterEstablishments).isEqualTo(5); // + the radiated restaurant in Saint-Laurent
        assertThat(stats.candidatesAfterStatus).isEqualTo(4);
        assertThat(report.asText()).contains("8 establishment rows read");
    }

    @Test
    void failsListingMissingFiles() throws Exception {
        Path zip = RegisterFixtures.zipFixtures(tmp, "Nom.csv", "DomaineValeur.csv");
        assertThatThrownBy(() -> run(zip, new QuebecBusinessRegisterImporter.Stats(), new ImportReport()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nom.csv (names)")
                .hasMessageContaining("DomaineValeur.csv (domainValues)")
                .hasMessageContaining("Etablissements.csv");
    }

    @Test
    void failsListingMissingColumns() throws Exception {
        Path zip = tmp.resolve("bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            RegisterFixtures.zipEntry(out, "Entreprise.csv", "NEQ,COD_STAT_IMMAT\n");
            RegisterFixtures.zipEntry(out, "Etablissements.csv", "NEQ,ADRESSE,VILLE\n1,a,b\n");
            RegisterFixtures.zipEntry(out, "Nom.csv", "NEQ,NOM_ASSUJ\n");
            RegisterFixtures.zipEntry(out, "DomaineValeur.csv", "TYP_DOM_VAL,COD_DOM_VAL,VAL_DOM_FRAN\n");
        }
        assertThatThrownBy(() -> run(zip, new QuebecBusinessRegisterImporter.Stats(), new ImportReport()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIGN1_ADR (line1)")
                .hasMessageContaining("COD_ACT_ECON (activityCode)")
                .hasMessageContaining("Columns found: [ADRESSE, NEQ, VILLE]");
    }

    @Test
    void rejectsAFileThatIsNotAZip() throws Exception {
        Path notZip = Files.writeString(tmp.resolve("x.zip"), "hello");
        assertThatThrownBy(() -> run(notZip, new QuebecBusinessRegisterImporter.Stats(), new ImportReport()))
                .hasMessageContaining("not a valid ZIP");
    }

    /**
     * Streaming proof. The real archive has millions of rows; the importer must never hold them.
     * We generate 300 000 establishment rows (+ as many enterprise and name rows, ~60 MB of CSV
     * uncompressed) of which only 30 match, and check that:
     * <ul>
     *   <li>every row of every file was read (stats.*Rows), and</li>
     *   <li>the only retained state is the 30 matching candidates (stats.candidatesAfter*),
     *       i.e. retention is O(matches), not O(rows).</li>
     * </ul>
     * The importer has no list/map of rows: it reads entries through ZipFile input streams, one CSV
     * record at a time (opencsv readNext), and only puts a row into the candidates map after the
     * city+sector filter. Peak heap is therefore independent of the archive size. (Heap usage is not
     * asserted: garbage from parsed rows makes such a measurement GC-dependent and flaky; the
     * counters below are the deterministic proof.)
     */
    @Test
    void streamsLargeArchivesKeepingOnlyMatchingRows() throws Exception {
        int rows = 300_000;
        int matching = 30;
        Path zip = tmp.resolve("big.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.setLevel(1);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(RegisterFixtures.entry(out, "Etablissements.csv"), StandardCharsets.UTF_8));
            w.write("NEQ,NO_SUF_ETAB,IND_ETAB_PRINC,IND_SALON_BRONZ,IND_VENTE_TABAC_DETL,IND_DISP,LIGN1_ADR,LIGN2_ADR,LIGN3_ADR,LIGN4_ADR,COD_ACT_ECON,DESC_ACT_ECON_ETAB,NO_ACT_ECON_ETAB,COD_ACT_ECON2,DESC_ACT_ECON_ETAB2,NO_ACT_ECON_ETAB2,NOM_ETAB\n");
            for (int i = 0; i < rows; i++) {
                boolean match = i % (rows / matching) == 0;
                String city = match ? "Montréal (Québec)" : (i % 2 == 0 ? "Sherbrooke (Québec)" : "Montréal (Québec)");
                String desc = match ? "Cabinets de dentistes" : "Fabrication de produits métalliques divers et variés";
                w.write(String.format("%010d,1,O,N,N,O,\"%d, rue Générée\",%s,,H1A 1A1,9999,%s,1,,,,ETAB %d\n", i, i, city, desc, i));
            }
            w.flush();
            out.closeEntry();
            w = new BufferedWriter(new OutputStreamWriter(RegisterFixtures.entry(out, "Entreprise.csv"), StandardCharsets.UTF_8));
            w.write("NEQ,COD_STAT_IMMAT,COD_INTVAL_EMPLO_QUE\n");
            for (int i = 0; i < rows; i++) w.write(String.format("%010d,IM,2\n", i));
            w.flush();
            out.closeEntry();
            w = new BufferedWriter(new OutputStreamWriter(RegisterFixtures.entry(out, "Nom.csv"), StandardCharsets.UTF_8));
            w.write("NEQ,NOM_ASSUJ,STAT_NOM,TYP_NOM_ASSUJ,DAT_FIN_NOM_ASSUJ\n");
            for (int i = 0; i < rows; i++) w.write(String.format("%010d,Entreprise générée numéro %d inc.,V,N,\n", i, i));
            w.flush();
            out.closeEntry();
            RegisterFixtures.zipEntry(out, "DomaineValeur.csv", "TYP_DOM_VAL,COD_DOM_VAL,VAL_DOM_FRAN\nINTVAL_EMPLO_QUE,2,De 1 à 5\n");
        }

        QuebecBusinessRegisterImporter.Stats stats = new QuebecBusinessRegisterImporter.Stats();
        long[] emitted = {0};
        importer.stream(zip, source, importer.parse(source), new ImportReport(), stats).forEach(b -> {
            emitted[0]++;
            return true;
        });

        assertThat(stats.establishmentRows).isEqualTo(rows);
        assertThat(stats.enterpriseRows).isEqualTo(rows);
        assertThat(stats.nameRows).isEqualTo(rows);
        assertThat(stats.candidatesAfterEstablishments).isEqualTo(matching);
        assertThat(stats.candidatesAfterStatus).isEqualTo(matching);
        assertThat(emitted[0]).isEqualTo(matching);
    }

    private static Business byNeq(List<Business> leads, String neq) {
        return leads.stream().filter(b -> neq.equals(b.getExternalId())).findFirst().orElseThrow();
    }

    @Test
    void v8ConfigParses() throws Exception {
        QuebecBusinessRegisterImporter.Settings s = importer.parse(source);
        assertThat(s.cityByNormalizedName()).containsEntry("saint laurent", "Montréal").containsEntry("brossard", "Brossard");
        assertThat(s.sectors()).extracting(QuebecBusinessRegisterImporter.Sector::label).contains("Dentistes", "Garderies");
        assertThat(s.activeStatuses()).containsExactly("IM");
        assertThat(source.getConfig()).containsKey("resourceId");
    }
}
