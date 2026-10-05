package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.ScraperJobRepository;
import com.econet.leads.repository.TenderRepository;
import com.econet.leads.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests (H2, PostgreSQL mode) of api-contract-2: phone enrichment, toEnrich, tenders API,
 * the register upload, and import jobs of the new sources (fixtures, and network failures).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeadSourcesApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired ScraperJobRepository scraperJobRepository;
    @Autowired ImportJobRunner jobRunner;
    @Autowired ImportJobTracker jobTracker;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SeaoTenderImporter seaoImporter;
    @Autowired CanadaBuysTenderImporter canadaBuysImporter;

    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        tenderRepository.deleteAll();
        scraperJobRepository.deleteAll();
        ensureUser("carol", User.UserRole.USER);
        ensureUser("vera", User.UserRole.VIEWER);
    }

    private void ensureUser(String username, User.UserRole role) {
        userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@example.com");
            u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
            u.setRole(role);
            u.setActive(true);
            return userRepository.save(u);
        });
    }

    private Business lead(String name, String phone, LeadStatus status) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Chantier");
        b.setAddressCity("Montréal");
        b.setPhone(phone);
        b.setDataSource("TEST");
        b.setLeadStatus(status);
        b.setDataQualityScore(30);
        b.setSourceDetails(new java.util.LinkedHashMap<>(Map.of("NEQ", "1160000001")));
        return businessRepository.save(b);
    }

    private DataSource source(String name, DataSource.SourceType type, Map<String, Object> config) {
        DataSource ds = dataSourceRepository.findBySourceName(name).orElseGet(DataSource::new);
        ds.setSourceName(name);
        ds.setSourceType(type);
        ds.setActive(true);
        ds.setConfig(config);
        return dataSourceRepository.save(ds);
    }

    private ScraperJob awaitJob(UUID jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ScraperJob job = scraperJobRepository.findById(jobId).orElseThrow();
            if (job.isTerminal()) return job;
            Thread.sleep(100);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private UUID jobId(MvcResult result) throws Exception {
        return UUID.fromString(new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // ------------------------------------------------------------------ phone enrichment

    @Test
    void patchPhoneNormalizesScoresAndLogsANote() throws Exception {
        Business noPhone = lead("Chantier – 1 rue Test", null, LeadStatus.NEW);

        mvc.perform(patch("/api/businesses/{id}/phone", noPhone.getId()).with(user("carol").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"+1 514-555-0123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("(514) 555-0123"))
                .andExpect(jsonPath("$.dataQualityScore").value(greaterThan(30)))
                .andExpect(jsonPath("$.sourceDetails.NEQ").value("1160000001"));

        Business saved = businessRepository.findById(noPhone.getId()).orElseThrow();
        assertThat(saved.getPhoneNormalized()).isEqualTo("5145550123");
        List<Contact> history = contactRepository.findAll();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getContactType()).isEqualTo(Contact.ContactType.NOTE);
        assertThat(history.get(0).getNotes()).startsWith("Numéro ajouté");

        // now callable: shows up in the queue
        mvc.perform(get("/api/leads/queue").with(user("carol").roles("USER")))
                .andExpect(jsonPath("$[*].id", hasItem(noPhone.getId().toString())));
    }

    @Test
    void patchPhoneRejectsInvalidNumbersAndViewers() throws Exception {
        Business noPhone = lead("Sans numéro", null, LeadStatus.NEW);
        for (String bad : List.of("555-0123", "514 555 01234", "+33 1 23 45 67 89", "abc", "(014) 555-0123")) {
            mvc.perform(patch("/api/businesses/{id}/phone", noPhone.getId()).with(user("carol").roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }
        mvc.perform(patch("/api/businesses/{id}/phone", noPhone.getId()).with(user("carol").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.phone").exists());
        mvc.perform(patch("/api/businesses/{id}/phone", noPhone.getId()).with(user("vera").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"5145550123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/businesses/{id}/phone", UUID.randomUUID()).with(user("carol").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"5145550123\"}"))
                .andExpect(status().isNotFound());
        assertThat(businessRepository.findById(noPhone.getId()).orElseThrow().getPhone()).isNull();
    }

    @Test
    void dashboardCountsLeadsToEnrich() throws Exception {
        lead("A", null, LeadStatus.NEW);
        lead("B", "", LeadStatus.CONTACTED);
        lead("C", null, LeadStatus.LOST);          // terminal: excluded
        lead("D", "(514) 555-0100", LeadStatus.NEW); // has a phone
        mvc.perform(get("/api/dashboard/summary").with(user("vera").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toEnrich").value(2));
        mvc.perform(get("/api/businesses").param("hasPhone", "false").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    // ------------------------------------------------------------------ tenders

    private void importSeaoFixture() throws Exception {
        DataSource ds = source("SEAO test", DataSource.SourceType.CKAN_API,
                new HashMap<>(Map.of("importer", SeaoTenderImporter.IMPORTER)));
        ScraperJob job = jobTracker.createPendingJob(ds);
        SeaoTenderImporter.Settings settings = seaoImporter.parse(ds);
        RecordStream<Tender> stream = sink -> {
            try (var in = getClass().getResourceAsStream("/fixtures/seao-mensuel-sample.json")) {
                seaoImporter.read(in, settings, new SeaoTenderImporter.Counts(), sink);
            }
        };
        jobRunner.runTenders(job.getId(), ds.getId(), ds.getSourceName(), stream, new ImportReport());
        assertThat(scraperJobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
    }

    private Tender importCanadaBuysFixture() throws Exception {
        DataSource ds = source("CanadaBuys test", DataSource.SourceType.CSV_DOWNLOAD,
                new HashMap<>(Map.of("importer", CanadaBuysTenderImporter.IMPORTER)));
        ScraperJob job = jobTracker.createPendingJob(ds);
        var settings = canadaBuysImporter.parse(ds);
        Path csv = tmp.resolve("open.csv");
        try (var in = getClass().getResourceAsStream("/fixtures/canadabuys-open-tenders.csv")) {
            Files.copy(in, csv, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // feed the fixture through the real streaming reader
        ImportReport report = new ImportReport();
        jobRunner.runTenders(job.getId(), ds.getId(), ds.getSourceName(), sink -> {
            try (var in = Files.newInputStream(csv)) {
                canadaBuysImporter.read(in, settings, report, sink);
            }
        }, report);
        ScraperJob done = scraperJobRepository.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
        assertThat(done.getRecordsAdded() + done.getRecordsUpdated()).isEqualTo(2);
        assertThat(done.getLog()).contains("2 kept");
        return tenderRepository.findAll().stream().filter(t -> t.getExternalId().equals("EP123-260001/A")).findFirst().orElseThrow();
    }

    @Test
    void seaoImportUpsertsByOcidAndIgnoresOlderReleases() throws Exception {
        importSeaoFixture();
        List<Tender> all = tenderRepository.findAll();
        assertThat(all).hasSize(2);
        Tender schools = tenderRepository.findBySourceAndExternalId(com.econet.leads.model.TenderSource.SEAO, "ocds-ec9k95-20170001").orElseThrow();
        assertThat(schools.getTitle()).doesNotContain("OLD TITLE");
        assertThat(schools.getClosingAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 14, 0));
        assertThat(schools.getMatchedKeywords()).contains("entretien ménager");
    }

    @Test
    void tendersApiListsFiltersPatchesAndSummarizes() throws Exception {
        Tender federal = importCanadaBuysFixture();
        importSeaoFixture();
        assertThat(tenderRepository.count()).isEqualTo(4);

        // read for every role; JSON shape of the contract
        mvc.perform(get("/api/tenders").with(user("vera").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].source").exists())
                .andExpect(jsonPath("$.content[0].matchedKeywords").isArray())
                .andExpect(jsonPath("$.content[0].status").value("NEW"));

        // default sort closingAt ASC, nulls last
        mvc.perform(get("/api/tenders").param("sortBy", "closingAt").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.content[0].externalId").value("ocds-ec9k95-20170001")) // 2026-10-16
                .andExpect(jsonPath("$.content[2].externalId").value("5X001-26-0042"))        // 2026-11-02
                .andExpect(jsonPath("$.content[3].externalId").value("ocds-ec9k95-20160002")); // award: contract end 2028-10-31

        mvc.perform(get("/api/tenders").param("source", "SEAO").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/tenders").param("q", "guy-favreau").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].buyer").value("Travaux publics et Services gouvernementaux Canada"));
        mvc.perform(get("/api/tenders").param("sortBy", "budget").with(user("vera").roles("VIEWER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tenders").param("status", "MAYBE").with(user("vera").roles("VIEWER")))
                .andExpect(status().isBadRequest());

        // the awarded SEAO contract carries the renewal date
        Tender award = tenderRepository.findBySourceAndExternalId(com.econet.leads.model.TenderSource.SEAO, "ocds-ec9k95-20160002").orElseThrow();
        mvc.perform(get("/api/tenders/{id}", award.getId()).with(user("vera").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeType").value("AWARD"))
                .andExpect(jsonPath("$.awardedTo").value("Conciergerie Exemple inc."))
                .andExpect(jsonPath("$.contractEndAt").value("2028-10-31T00:00:00"))
                .andExpect(jsonPath("$.closingAt").value("2028-10-31T00:00:00"))
                .andExpect(jsonPath("$.category").value(startsWith("Contrat octroyé")));

        // follow-up
        mvc.perform(patch("/api/tenders/{id}", federal.getId()).with(user("vera").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BIDDING\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/tenders/{id}", federal.getId()).with(user("carol").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BIDDING\",\"notes\":\"Visite le 12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BIDDING"))
                .andExpect(jsonPath("$.notes").value("Visite le 12"));
        mvc.perform(patch("/api/tenders/{id}", federal.getId()).with(user("carol").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tenders/{id}", UUID.randomUUID()).with(user("vera").roles("VIEWER")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/tenders").param("status", "BIDDING,SUBMITTED").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.totalElements").value(1));

        // re-import keeps the team's status and notes
        importCanadaBuysFixture();
        Tender again = tenderRepository.findById(federal.getId()).orElseThrow();
        assertThat(again.getStatus()).isEqualTo(TenderStatus.BIDDING);
        assertThat(again.getNotes()).isEqualTo("Visite le 12");

        mvc.perform(get("/api/tenders/summary").with(user("vera").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidding").value(1))
                .andExpect(jsonPath("$.submitted").value(0))
                .andExpect(jsonPath("$.won").value(0))
                .andExpect(jsonPath("$.open").isNumber())
                .andExpect(jsonPath("$.closingThisWeek").isNumber());
        // an award with a future contract end is listed by openOnly but not counted as an open notice
        assertThat(tenderRepository.countOpen(LocalDateTime.of(2027, 1, 1, 0, 0), List.of(TenderStatus.IGNORED))).isZero();
    }

    @Test
    void openOnlyAndSummaryUseTheCurrentTime() throws Exception {
        tender("T-open", LocalDateTime.now().plusDays(3), TenderStatus.NEW);
        tender("T-later", LocalDateTime.now().plusDays(20), TenderStatus.REVIEWING);
        tender("T-closed", LocalDateTime.now().minusDays(1), TenderStatus.SUBMITTED);
        tender("T-ignored", LocalDateTime.now().plusDays(2), TenderStatus.IGNORED);
        tender("T-won", null, TenderStatus.WON);

        mvc.perform(get("/api/tenders").param("openOnly", "true").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].externalId", contains("T-ignored", "T-open", "T-later")));
        mvc.perform(get("/api/tenders/summary").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.closingThisWeek").value(1))
                .andExpect(jsonPath("$.submitted").value(1))
                .andExpect(jsonPath("$.won").value(1));
        mvc.perform(get("/api/tenders").param("sortBy", "createdAt").param("sortDirection", "DESC").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.content", hasSize(5)));
    }

    private Tender tender(String id, LocalDateTime closing, TenderStatus status) {
        Tender t = new Tender();
        t.setSource(com.econet.leads.model.TenderSource.CANADABUYS);
        t.setExternalId(id);
        t.setTitle("Nettoyage " + id);
        t.setUrl("https://example.org/" + id);
        t.setClosingAt(closing);
        t.setStatus(status);
        t.setMatchedKeywords(List.of("nettoyage"));
        return tenderRepository.save(t);
    }

    // ------------------------------------------------------------------ register upload

    private DataSource registerSource() throws Exception {
        Map<String, Object> config = RegisterFixtures.v8Config("Registre des entreprises du Québec");
        DataSource ds = source("Registre des entreprises du Québec", DataSource.SourceType.BULK_FILE, config);
        ds.setActive(false); // as shipped: upload works while inactive
        return dataSourceRepository.save(ds);
    }

    private byte[] registerZip() throws Exception {
        return Files.readAllBytes(RegisterFixtures.zipFixtures(tmp));
    }

    @Test
    void registerUploadImportsInTheBackground() throws Exception {
        DataSource ds = registerSource();
        MockMultipartFile file = new MockMultipartFile("file", "registre.zip", "application/zip", registerZip());

        mvc.perform(multipart("/api/data-sources/{id}/upload", ds.getId()).file(file).with(user("carol").roles("USER")))
                .andExpect(status().isForbidden());

        MvcResult accepted = mvc.perform(multipart("/api/data-sources/{id}/upload", ds.getId()).file(file).with(user("admin").roles("ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(anyOf(is("PENDING"), is("RUNNING"))))
                .andExpect(jsonPath("$.sourceName").value("Registre des entreprises du Québec"))
                .andReturn();
        ScraperJob job = awaitJob(jobId(accepted));
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
        assertThat(job.getRecordsAdded()).isEqualTo(4);
        assertThat(job.getLog()).contains("8 establishment rows read").contains("uploaded file");

        Business dentist = businessRepository.findFirstByExternalIdAndDataSourceOrderByCreatedAtAsc("1160000001", ds.getSourceName()).orElseThrow();
        assertThat(dentist.getSourceDetails()).containsEntry("Secteur", "Dentistes").containsEntry("Employés", "De 6 à 10");
        mvc.perform(get("/api/businesses/{id}", dentist.getId()).with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.sourceDetails.NEQ").value("1160000001"))
                .andExpect(jsonPath("$.phone").value(nullValue()));
        mvc.perform(get("/api/dashboard/summary").with(user("vera").roles("VIEWER")))
                .andExpect(jsonPath("$.toEnrich").value(4));

        // the temp file is gone once the job is done
        try (var files = Files.list(com.econet.leads.integration.support.ImportFiles.directory())) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith("upload-")).toList()).isEmpty();
        }
    }

    @Test
    void registerUploadRejectsNonZipFilesAndOtherSources() throws Exception {
        DataSource ds = registerSource();
        mvc.perform(multipart("/api/data-sources/{id}/upload", ds.getId())
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", "NEQ\n1\n".getBytes(StandardCharsets.UTF_8)))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("not a ZIP")));

        DataSource other = source("Autre source", DataSource.SourceType.MANUAL, new HashMap<>());
        mvc.perform(multipart("/api/data-sources/{id}/upload", other.getId())
                        .file(new MockMultipartFile("file", "r.zip", "application/zip", registerZip()))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/data-sources/{id}/upload", UUID.randomUUID())
                        .file(new MockMultipartFile("file", "r.zip", "application/zip", registerZip()))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerUploadIs409WhileAnImportRuns() throws Exception {
        DataSource ds = registerSource();
        ScraperJob running = jobTracker.createPendingJob(ds);
        mvc.perform(multipart("/api/data-sources/{id}/upload", ds.getId())
                        .file(new MockMultipartFile("file", "r.zip", "application/zip", registerZip()))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isConflict());
        jobTracker.fail(running.getId(), "test cleanup");
    }

    // ------------------------------------------------------------------ jobs of the new sources

    @Test
    void permitsJobPersistsOneLeadPerAddressWithSourceDetails() throws Exception {
        DataSource ds = source("Permis test", DataSource.SourceType.CKAN_API, new HashMap<>(Map.of(
                "importer", MontrealPermitImporter.IMPORTER, "resourceId", "5232a72d-235a-48eb-ae20-bb9d501300ad")));
        CkanApiClient ckan = mock(CkanApiClient.class);
        String body = new String(getClass().getResourceAsStream("/fixtures/montreal-permits-datastore.json").readAllBytes(), StandardCharsets.UTF_8);
        when(ckan.fetchDatastorePage(anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new CkanApiClient().parseDatastorePage(body, "x"));
        ZoneId mtl = ZoneId.of("America/Montreal");
        MontrealPermitImporter importer = new MontrealPermitImporter(ckan,
                Clock.fixed(LocalDateTime.of(2026, 10, 5, 12, 0).atZone(mtl).toInstant(), mtl));
        ImportReport report = new ImportReport();
        ScraperJob job = jobTracker.createPendingJob(ds);

        jobRunner.runBusinesses(job.getId(), ds.getId(), ds.getSourceName(), importer.stream(ds, importer.parse(ds), report), report);

        ScraperJob done = scraperJobRepository.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
        assertThat(done.getRecordsProcessed()).isEqualTo(3);
        assertThat(done.getRecordsAdded()).isEqualTo(2);   // the second permit at 1250 rue Exemple updates the same lead
        assertThat(done.getRecordsUpdated()).isEqualTo(1);
        assertThat(done.getLog()).contains("cout_travaux_estimes");
        List<Business> leads = businessRepository.findAll();
        assertThat(leads).extracting(Business::getBusinessType).containsOnly("Chantier");
        Business office = leads.stream().filter(b -> b.getBusinessName().contains("1250")).findFirst().orElseThrow();
        assertThat(office.getSourceDetails()).containsEntry("Arrondissement", "Ville-Marie").containsKey("Date du permis");
    }

    @Test
    void networkFailuresFailTheJobWithAClearMessage() throws Exception {
        // nothing listens on port 9: connection refused, like the blocked portals in CI sandboxes
        DataSource permits = source("Permis réseau", DataSource.SourceType.CKAN_API, new HashMap<>(Map.of(
                "importer", MontrealPermitImporter.IMPORTER, "resourceId", "5232a72d-235a-48eb-ae20-bb9d501300ad",
                "ckanBaseUrl", "http://127.0.0.1:9/api/3/action/")));
        DataSource canadaBuys = source("CanadaBuys réseau", DataSource.SourceType.CSV_DOWNLOAD, new HashMap<>(Map.of(
                "importer", CanadaBuysTenderImporter.IMPORTER, "csvUrl", "http://127.0.0.1:9/open.csv")));
        DataSource seao = source("SEAO réseau", DataSource.SourceType.CKAN_API, new HashMap<>(Map.of(
                "importer", SeaoTenderImporter.IMPORTER, "ckanBaseUrl", "http://127.0.0.1:9/api/3/action/")));

        for (DataSource ds : List.of(permits, canadaBuys, seao)) {
            MvcResult accepted = mvc.perform(post("/api/data-sources/{id}/import", ds.getId()).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isAccepted())
                    .andReturn();
            ScraperJob job = awaitJob(jobId(accepted));
            assertThat(job.getStatus()).isEqualTo(ScraperJob.JobStatus.FAILED);
            assertThat(job.getErrors()).contains("127.0.0.1:9");
            assertThat(job.getCompletedAt()).isNotNull();
        }
    }

    @Test
    void brokenConfigIs400AndNotAJob() throws Exception {
        DataSource ds = source("Permis sans ressource", DataSource.SourceType.CKAN_API,
                new HashMap<>(Map.of("importer", MontrealPermitImporter.IMPORTER)));
        mvc.perform(post("/api/data-sources/{id}/import", ds.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("resourceId")));
        assertThat(scraperJobRepository.count()).isZero();
    }

    @Test
    void viewerCannotTouchDataSources() throws Exception {
        mvc.perform(get("/api/data-sources").with(user("vera").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenders/summary").with(user("vera").roles("VIEWER"))).andExpect(status().isOk());
    }
}
