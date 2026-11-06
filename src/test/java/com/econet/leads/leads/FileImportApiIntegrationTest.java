package com.econet.leads.leads;

import com.econet.leads.integration.fileimport.HeaderSynonyms;
import com.econet.leads.integration.fileimport.ImportFileStore;
import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Addendum 4 B: POST /api/imports/files, GET /api/imports/{id}, POST /api/imports/{id}/run. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileImportApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ImportFileStore store;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

    User alice;

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        alice = ensureUser("alice", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);
    }

    // ------------------------------------------------------------------ upload / detection

    @Test
    void semicolonCsvWithBomAndAccentsIsDetectedWithSuggestedMapping() throws Exception {
        String csv = "﻿Nom de l'entreprise;Téléphone;Ville;Adresse;Courriel;Site web;Catégorie;Code postal;Commentaire;Responsable\n"
                + "Café Étoile;514 555-0101;Montréal;12 rue Principale;info@etoile.example;etoile.example;Restaurant;H2X 1Y4;Ouvre tôt;Marie\n"
                + "\"Boulangerie, Chez Léa\";450-555-0102;Laval;3 av. Cartier;;;Commerce;H7N 2A1;;\n";
        JsonNode info = upload("clients.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(info.get("importId").asText()).isNotBlank();
        assertThat(info.get("fileName").asText()).isEqualTo("clients.csv");
        assertThat(info.get("rowCount").asInt()).isEqualTo(2);
        assertThat(info.get("columns")).extracting(JsonNode::asText).containsExactly("Nom de l'entreprise", "Téléphone",
                "Ville", "Adresse", "Courriel", "Site web", "Catégorie", "Code postal", "Commentaire", "Responsable");
        assertThat(info.get("sampleRows")).hasSize(2);
        assertThat(info.get("sampleRows").get(1).get(0).asText()).isEqualTo("Boulangerie, Chez Léa");
        JsonNode mapping = info.get("suggestedMapping");
        assertThat(mapping.get("businessName").asText()).isEqualTo("Nom de l'entreprise");
        assertThat(mapping.get("phone").asText()).isEqualTo("Téléphone");
        assertThat(mapping.get("addressCity").asText()).isEqualTo("Ville");
        assertThat(mapping.get("addressStreet").asText()).isEqualTo("Adresse");
        assertThat(mapping.get("email").asText()).isEqualTo("Courriel");
        assertThat(mapping.get("website").asText()).isEqualTo("Site web");
        assertThat(mapping.get("businessType").asText()).isEqualTo("Catégorie");
        assertThat(mapping.get("postalCode").asText()).isEqualTo("Code postal");
        assertThat(mapping.get("notes").asText()).isEqualTo("Commentaire");
        assertThat(mapping.get("decisionMaker").asText()).isEqualTo("Responsable");

        mvc.perform(get("/api/imports/{id}", info.get("importId").asText()).with(ALICE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rowCount").value(2));
    }

    @Test
    void windows1252TabSeparatedTextAndNullMappingForUnknownColumns() throws Exception {
        String tsv = "Entreprise\tTel\tNotes internes\tCouleur\nÉpicerie Du Parc\t514-555-0111\tà rappeler\tbleu\n";
        JsonNode info = upload("export.tsv", tsv.getBytes(Charset.forName("windows-1252")));
        assertThat(info.get("columns")).extracting(JsonNode::asText).containsExactly("Entreprise", "Tel", "Notes internes", "Couleur");
        assertThat(info.get("sampleRows").get(0).get(0).asText()).isEqualTo("Épicerie Du Parc");
        assertThat(info.get("suggestedMapping").get("businessName").asText()).isEqualTo("Entreprise");
        assertThat(info.get("suggestedMapping").get("phone").asText()).isEqualTo("Tel");
        assertThat(info.get("suggestedMapping").get("notes").asText()).isEqualTo("Notes internes");
        assertThat(info.get("suggestedMapping").get("email").isNull()).isTrue();
        assertThat(info.get("suggestedMapping").get("website").isNull()).isTrue();
        assertThat(info.get("suggestedMapping").size()).isEqualTo(HeaderSynonyms.FIELDS.size());
    }

    @Test
    void xlsxFirstSheetIsRead() throws Exception {
        JsonNode info = upload("prospects.xlsx", xlsx());
        assertThat(info.get("rowCount").asInt()).isEqualTo(3);
        assertThat(info.get("columns")).extracting(JsonNode::asText).containsExactly("Company", "Phone", "City", "Email");
        assertThat(info.get("sampleRows").get(0)).extracting(JsonNode::asText)
                .containsExactly("Clinique Dentaire Nord", "5145550123", "Montréal", "nord@clinique.example");
        assertThat(info.get("suggestedMapping").get("businessName").asText()).isEqualTo("Company");
        assertThat(info.get("suggestedMapping").get("phone").asText()).isEqualTo("Phone");
    }

    @Test
    void rejectsUnsupportedEmptyOversizedAndHeaderOnlyFiles() throws Exception {
        mvc.perform(multipart("/api/imports/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes())).with(ALICE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(multipart("/api/imports/files").file(new MockMultipartFile("file", "a.csv", "text/csv", new byte[0])).with(ALICE))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/imports/files").file(new MockMultipartFile("file", "a.csv", "text/csv", "Nom;Tel\n".getBytes())).with(ALICE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("aucune ligne")));
        mvc.perform(multipart("/api/imports/files").file(new MockMultipartFile("file", "fake.xlsx", "application/octet-stream", "not a zip".getBytes())).with(ALICE))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/imports/files").file(new MockMultipartFile("file", "big.csv", "text/csv", new byte[10 * 1024 * 1024 + 1])).with(ALICE))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/imports/{id}", UUID.randomUUID()).with(ALICE)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ run

    @Test
    void runImportsValidRowsCountsInvalidOnesAndSkipsDuplicatesWhenAsked() throws Exception {
        existing("Café Étoile", "Montréal", "5145550101");
        String csv = "Nom;Tel;Ville;Adresse;Courriel;Contact;Commentaire\n"
                + "Café Étoile;514 555-0101;Montréal;;;;doublon connu\n"                 // 2 duplicate of existing
                + "Garage Beaulieu;450-555-0133;Laval;;;Paul;Gros garage\n"             // 3 ok
                + ";514-555-0144;Montréal;;;;sans nom\n"                                  // 4 invalid: no name
                + "Atelier Seul;;Montréal;;;;sans contact\n"                              // 5 invalid: nothing to reach
                + "Atelier Mail;;Montréal;;mail@atelier.example;;;\n"                     // 6 ok (email)
                + "Boutique Rue;;Québec;55 rue Saint-Jean;;;;\n"                         // 7 ok (address)
                + "\n"                                                                    // blank, ignored
                + "Garage Beaulieu;450-555-0133;Laval;;;;;\n";                           // 9 duplicate of row 3
        JsonNode info = upload("leads.csv", csv.getBytes(StandardCharsets.UTF_8));
        String id = info.get("importId").asText();

        String body = json.writeValueAsString(Map.of(
                "mapping", Map.of("businessName", "Nom", "phone", "Tel", "addressCity", "Ville", "addressStreet", "Adresse",
                        "email", "Courriel", "decisionMaker", "Contact", "notes", "Commentaire"),
                "defaults", Map.of("businessType", "Garage", "sourceName", "Salon mars"),
                "skipDuplicates", true, "assignToMe", true));
        JsonNode job = json.readTree(mvc.perform(post("/api/imports/{id}/run", id).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceName").value("Import – Salon mars"))
                .andReturn().getResponse().getContentAsString());

        JsonNode done = awaitJob(job.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("recordsAdded").asInt()).isEqualTo(3);
        assertThat(done.get("recordsUpdated").asInt()).isEqualTo(0);
        assertThat(done.get("recordsProcessed").asInt()).isEqualTo(3);
        String log = done.get("log").asText();
        assertThat(log).startsWith("Résumé: 7 lignes lues · 3 ajoutés · 0 mis à jour · 2 ignorés (doublons) · 2 invalides · 0 erreurs");
        assertThat(log).contains("Ligne 4 invalide: nom manquant")
                .contains("Ligne 5 invalide: ni téléphone, ni courriel, ni adresse");

        DataSource source = dataSourceRepository.findBySourceName("Import – Salon mars").orElseThrow();
        assertThat(source.getSourceType()).isEqualTo(DataSource.SourceType.MANUAL_FILE);
        assertThat(source.getActive()).isFalse();

        List<Business> imported = businessRepository.findAll().stream().filter(b -> "Import – Salon mars".equals(b.getDataSource())).toList();
        assertThat(imported).extracting(Business::getBusinessName).containsExactlyInAnyOrder("Garage Beaulieu", "Atelier Mail", "Boutique Rue");
        Business garage = imported.stream().filter(b -> b.getBusinessName().equals("Garage Beaulieu")).findFirst().orElseThrow();
        assertThat(garage.getBusinessType()).isEqualTo("Garage");
        assertThat(garage.getExternalId()).isEqualTo(id + ":3");
        assertThat(garage.getAssignedTo().getUsername()).isEqualTo("alice");
        assertThat(garage.getPhone()).isEqualTo("(450) 555-0133");
        assertThat(garage.getSourceDetails()).containsEntry("Notes", "Gros garage").containsEntry("Contact", "Paul")
                .containsEntry("Fichier", "leads.csv");
        assertThat(garage.getQualification()).containsEntry("decisionMaker", "Paul");

        // the temp file and the upload are gone after the run
        mvc.perform(get("/api/imports/{id}", id).with(ALICE)).andExpect(status().isNotFound());
        mvc.perform(post("/api/imports/{id}/run", id).with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutSkipDuplicatesMatchingRowsUpdateTheExistingLead() throws Exception {
        existing("Café Étoile", "Montréal", null);
        JsonNode info = upload("u.csv", "Nom,Telephone,Ville\nCafé Étoile,514-555-0199,Montréal\nNouveau Resto,514-555-0200,Montréal\n".getBytes(StandardCharsets.UTF_8));
        String body = json.writeValueAsString(Map.of(
                "mapping", info.get("suggestedMapping"),
                "defaults", Map.of("sourceName", "Mise à jour")));
        JsonNode job = json.readTree(mvc.perform(post("/api/imports/{id}/run", info.get("importId").asText()).with(ALICE)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        JsonNode done = awaitJob(job.get("id").asText());
        assertThat(done.get("recordsAdded").asInt()).isEqualTo(1);
        assertThat(done.get("recordsUpdated").asInt()).isEqualTo(1);
        assertThat(done.get("log").asText()).contains("1 ajoutés · 1 mis à jour");
        assertThat(businessRepository.findAll().stream().filter(b -> "Café Étoile".equals(b.getBusinessName())).findFirst().orElseThrow().getPhone())
                .isEqualTo("(514) 555-0199");
        // unassigned: assignToMe was not set
        assertThat(businessRepository.findAll().stream().filter(b -> "Nouveau Resto".equals(b.getBusinessName())).findFirst().orElseThrow().getAssignedTo()).isNull();
    }

    @Test
    void invalidRunRequestsAreRejected() throws Exception {
        JsonNode info = upload("v.csv", "Nom,Tel\nA B,514-555-0100\n".getBytes(StandardCharsets.UTF_8));
        String id = info.get("importId").asText();
        run(id, Map.of("mapping", Map.of("phone", "Tel"), "defaults", Map.of("sourceName", "x"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("businessName")));
        run(id, Map.of("mapping", Map.of("businessName", "Nope"), "defaults", Map.of("sourceName", "x"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Nope")));
        run(id, Map.of("mapping", Map.of("businessName", "Nom", "colour", "Tel"), "defaults", Map.of("sourceName", "x"))).andExpect(status().isBadRequest());
        run(id, Map.of("mapping", Map.of("businessName", "Nom"), "defaults", Map.of("businessType", "T"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("sourceName")));
        run(UUID.randomUUID().toString(), Map.of("mapping", Map.of("businessName", "Nom"), "defaults", Map.of("sourceName", "x")))
                .andExpect(status().isNotFound());
        // still usable after the refused attempts
        mvc.perform(get("/api/imports/{id}", id).with(ALICE)).andExpect(status().isOk());
    }

    @Test
    void uploadsExpireAfterOneHour() throws Exception {
        JsonNode info = upload("t.csv", "Nom,Tel\nA B,514-555-0100\n".getBytes(StandardCharsets.UTF_8));
        int before = store.size();
        assertThat(store.sweep(Instant.now().plusSeconds(30 * 60))).isZero();
        assertThat(store.size()).isEqualTo(before);
        assertThat(store.sweep(Instant.now().plusSeconds(61 * 60))).isGreaterThanOrEqualTo(1); // other tests leave uploads too
        mvc.perform(get("/api/imports/{id}", info.get("importId").asText()).with(ALICE)).andExpect(status().isNotFound());
    }

    @Test
    void viewerCannotUseTheImportEndpoints() throws Exception {
        mvc.perform(multipart("/api/imports/files").file(new MockMultipartFile("file", "a.csv", "text/csv", "Nom\nA\n".getBytes())).with(VIEWER))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/imports/{id}", UUID.randomUUID()).with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/imports/{id}/run", UUID.randomUUID()).with(VIEWER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void headerSynonymsPickTheRightColumnOnlyOnce() {
        Map<String, String> m = HeaderSynonyms.suggest(List.of("Nom du contact", "Nom", "Téléphone 2", "Adresse courriel", "Adresse", "Prov."));
        assertThat(m.get("decisionMaker")).isEqualTo("Nom du contact");
        assertThat(m.get("businessName")).isEqualTo("Nom");
        assertThat(m.get("phone")).isEqualTo("Téléphone 2");
        assertThat(m.get("email")).isEqualTo("Adresse courriel");
        assertThat(m.get("addressStreet")).isEqualTo("Adresse");
        assertThat(m.get("addressCity")).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions run(String id, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/imports/{id}/run", id).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode upload(String name, byte[] content) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/imports/files")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", content)).with(ALICE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Polls the job as a USER: following one's own import must not need admin rights. */
    private JsonNode awaitJob(String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode job = json.readTree(mvc.perform(get("/api/scraper-jobs/{id}", jobId).with(ALICE))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            String status = job.get("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job did not finish");
    }

    private byte[] xlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Prospects");
            String[][] rows = {
                    {"Company", "Phone", "City", "Email"},
                    {"Clinique Dentaire Nord", null, "Montréal", "nord@clinique.example"},
                    {"Pharmacie Centrale", null, "Laval", null},
                    {"Gym Fitness Plus", null, "Longueuil", null}};
            double[] phones = {5145550123d, 4505550124d, 4505550125d};
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (r > 0 && c == 1) {
                        row.createCell(c).setCellValue(phones[r - 1]); // numeric cell, as Excel stores phone numbers
                    } else if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            wb.createSheet("Autre").createRow(0).createCell(0).setCellValue("ignorée");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void existing(String name, String city, String phoneDigits) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Restaurant");
        b.setAddressCity(city);
        if (phoneDigits != null) {
            b.setPhone(phoneDigits);
            b.setPhoneNormalized(phoneDigits);
        }
        b.setDataSource("Test");
        businessRepository.save(b);
    }

    private User ensureUser(String username, User.UserRole role) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(role);
            u.setActive(true);
            return userRepository.save(u);
        });
    }
}
