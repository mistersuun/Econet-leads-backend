package com.econet.leads.leads;

import com.econet.leads.integration.website.WebsiteDb;
import com.econet.leads.integration.website.WebsitePoller;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.ScraperJobRepository;
import com.econet.leads.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Addendum 4 D with an H2 database standing in for the website's Postgres: configured source,
 * incremental and idempotent import, lead fields, SSE announcements, notifications, poller, and the
 * guarantee that the website database is only read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.website.db.url=jdbc:h2:mem:website;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "app.website.db.username=sa",
        "app.website.poll-minutes=5"
})
class WebsiteRequestsIntegrationTest {

    static final String WEBSITE_URL = "jdbc:h2:mem:website;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired ScraperJobRepository scraperJobRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SseEventService sse;
    @Autowired WebsiteDb websiteDb;
    @Autowired WebsitePoller poller;

    DataSource source;

    @BeforeEach
    void setUp() throws Exception {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        scraperJobRepository.deleteAll();
        ensureUser("admin", User.UserRole.ADMIN);
        ensureUser("alice", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);

        try (Connection c = DriverManager.getConnection(WEBSITE_URL, "sa", ""); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS booking_requests (
                      id VARCHAR(40) PRIMARY KEY, first_name VARCHAR(100), last_name VARCHAR(100), phone VARCHAR(40),
                      email VARCHAR(255), address VARCHAR(255), city VARCHAR(100), postal_code VARCHAR(10),
                      property_type VARCHAR(40), property_size VARCHAR(40), service_name VARCHAR(100),
                      preferred_date DATE, preferred_time VARCHAR(20), frequency VARCHAR(40),
                      additional_services VARCHAR(500), total DECIMAL(10,2), special_instructions TEXT,
                      language VARCHAR(5), created_at TIMESTAMP NOT NULL)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS contact_submissions (
                      id VARCHAR(40) PRIMARY KEY, name VARCHAR(255), phone VARCHAR(40), email VARCHAR(255),
                      service_type VARCHAR(100), property_size VARCHAR(40), preferred_date VARCHAR(40), message TEXT,
                      urgent_request BOOLEAN, created_at TIMESTAMP NOT NULL)""");
            s.execute("DELETE FROM booking_requests");
            s.execute("DELETE FROM contact_submissions");
        }

        source = dataSourceRepository.findBySourceName("Site web – demandes").orElseGet(DataSource::new);
        source.setSourceName("Site web – demandes");
        source.setSourceType(DataSource.SourceType.WEBSITE_DB);
        source.setActive(true);
        source.setLastSync(null);
        source.setConfig(java.util.Map.of("importer", "WEBSITE_REQUESTS"));
        source = dataSourceRepository.save(source);
    }

    @Test
    void sourceIsConfiguredAndThePoolIsReadOnly() throws Exception {
        assertThat(websiteDb.isConfigured()).isTrue();
        mvc.perform(get("/api/data-sources").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sourceName=='Site web – demandes')].configured", contains(true)));
        assertThat(websiteDb.isReadOnlyPool()).isTrue();
    }

    @Test
    void importsBookingsAndContactsAsInterestedLeadsDueNow() throws Exception {
        insertBooking("b1", "Marie", "Tremblay", "514-555-0201", "marie@example.test", "261 rue Fleury", "Montréal", "H3L 1T2",
                "house", "1500", "Ménage régulier", "2026-11-20", "09:00", "bi-weekly", "[\"fenêtres\",\"four\"]", "261.00",
                "Chien dans la maison", "fr", minutesAgo(30));
        insertBooking("b2", "Jean", "Gagnon", "450-555-0202", null, "5 boul. Industriel", "Laval", null,
                "office", "4000", "Bureau", "2026-11-22", "18:00", "weekly", null, "890.50", null, "en", minutesAgo(20));
        insertContact("c1", "Sophie Roy", "514-555-0203", "sophie@example.test", "Après construction", "800", "2026-11-25", "Besoin d'un devis rapide", false, minutesAgo(10));
        insertContact("c2", "Luc Pelletier", "418-555-0204", null, "Urgence", null, null, "Dégât d'eau ce matin", true, minutesAgo(5));
        long lastEvent = sse.lastEventId();

        JsonNode job = runImport();
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("recordsAdded").asInt()).isEqualTo(4);
        assertThat(job.get("recordsUpdated").asInt()).isZero();

        Business marie = byExternalId("booking:b1");
        assertThat(marie.getBusinessName()).isEqualTo("Marie Tremblay");
        assertThat(marie.getBusinessType()).isEqualTo("Demande web – résidentiel");
        assertThat(marie.getDataSource()).isEqualTo("Site web");
        assertThat(marie.getLeadStatus()).isEqualTo(LeadStatus.INTERESTED);
        assertThat(marie.getNextFollowUpAt()).isBetween(LocalDateTime.now().minusMinutes(2), LocalDateTime.now().plusMinutes(1));
        assertThat(marie.getEstimatedValue()).isEqualByComparingTo("261");
        assertThat(marie.getPhone()).isEqualTo("(514) 555-0201");
        assertThat(marie.getAddressStreet()).isEqualTo("261 Rue Fleury");
        assertThat(marie.getAddressCity()).isEqualTo("Montréal");
        assertThat(marie.getSourceDetails()).containsEntry("Service", "Ménage régulier")
                .containsEntry("Date souhaitée", "2026-11-20 09:00").containsEntry("Fréquence", "bi-weekly")
                .containsEntry("Superficie", "1500").containsEntry("Options", "fenêtres, four")
                .containsEntry("Estimation", "261 $").containsEntry("Instructions", "Chien dans la maison")
                .containsEntry("Langue", "fr");
        assertThat(marie.getQualification()).containsEntry("email", "marie@example.test").containsEntry("language", "fr")
                .containsEntry("frequencyWanted", "bi-weekly").containsEntry("notes", "Chien dans la maison");
        assertThat(marie.getAssignedTo()).isNull();

        assertThat(byExternalId("booking:b2").getBusinessType()).isEqualTo("Demande web – commercial");
        assertThat(byExternalId("booking:b2").getEstimatedValue()).isEqualByComparingTo("890.50");

        Business urgent = byExternalId("contact:c2");
        assertThat(urgent.getBusinessType()).isEqualTo("Demande web – contact");
        assertThat(urgent.getSourceDetails()).containsEntry("Urgent", "oui").containsEntry("Message", "Dégât d'eau ce matin")
                .containsEntry("Service", "Urgence");
        assertThat(urgent.getLeadStatus()).isEqualTo(LeadStatus.INTERESTED);
        assertThat(byExternalId("contact:c1").getSourceDetails()).doesNotContainKey("Urgent");

        // history notes
        List<Contact> notes = contactRepository.findAll();
        assertThat(notes).hasSize(4).allMatch(c -> c.getContactType() == Contact.ContactType.NOTE);
        assertThat(notes).extracting(Contact::getNotes).contains("Demande de réservation reçue sur le site", "Dégât d'eau ce matin");

        // one SSE event per new lead
        List<SseEventService.StoredEvent> inbound = sse.eventsAfter(lastEvent).stream().filter(e -> e.type().equals("lead.inbound")).toList();
        assertThat(inbound).hasSize(4);
        JsonNode first = json.readTree(inbound.stream().filter(e -> e.json().contains("Marie Tremblay")).findFirst().orElseThrow().json());
        assertThat(first.get("leadId").asText()).isEqualTo(marie.getId().toString());
        assertThat(first.get("kind").asText()).isEqualTo("booking");
        assertThat(first.get("leadName").asText()).isEqualTo("Marie Tremblay");
        assertThat(first.get("at").asText()).isNotBlank();
        assertThat(inbound.stream().filter(e -> e.json().contains("\"kind\":\"contact\"")).count()).isEqualTo(2);

        // notifications: one inbound_request per lead, no duplicate follow_up_due
        String notifications = mvc.perform(get("/api/notifications").with(ALICE)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode list = json.readTree(notifications);
        assertThat(list).hasSize(4);
        JsonNode marieNotification = null;
        for (JsonNode n : list) {
            assertThat(n.get("kind").asText()).isEqualTo("inbound_request");
            if (n.get("title").asText().equals("Marie Tremblay")) marieNotification = n;
        }
        assertThat(marieNotification).isNotNull();
        assertThat(marieNotification.get("body").asText()).isEqualTo("Nouvelle demande du site · Résidentiel · 261 $");
        assertThat(marieNotification.get("leadId").asText()).isEqualTo(marie.getId().toString());
    }

    @Test
    void importIsIdempotentAndIncremental() throws Exception {
        insertBooking("b1", "Marie", "Tremblay", "514-555-0201", null, "261 rue Fleury", "Montréal", null,
                "house", null, "Ménage", null, null, null, null, "100", null, "fr", minutesAgo(30));
        runImport();
        long mark = sse.lastEventId();
        int leads = businessRepository.findAll().size();

        // same request seen again (overlap window): no new lead, no event, status untouched by the import
        Business marie = byExternalId("booking:b1");
        marie.setLeadStatus(LeadStatus.QUOTE_SENT);
        businessRepository.save(marie);
        JsonNode second = runImport();
        assertThat(second.get("recordsAdded").asInt()).isZero();
        assertThat(second.get("recordsUpdated").asInt()).isEqualTo(1);
        assertThat(businessRepository.findAll()).hasSize(leads);
        assertThat(byExternalId("booking:b1").getLeadStatus()).isEqualTo(LeadStatus.QUOTE_SENT);
        assertThat(sse.eventsAfter(mark).stream().filter(e -> e.type().equals("lead.inbound"))).isEmpty();
        assertThat(contactRepository.findAll()).hasSize(1);

        // a request older than lastSync - 1 day is not read again; a new one is
        insertBooking("old", "Vieux", "Client", "514-555-0299", null, "1 rue Vieille", "Montréal", null,
                "house", null, "Ménage", null, null, null, null, "50", null, "fr", LocalDateTime.now().minusDays(3));
        insertContact("c9", "Nouveau Contact", "514-555-0298", null, "Bureau", null, null, "Bonjour", false, minutesAgo(1));
        JsonNode third = runImport();
        assertThat(third.get("recordsProcessed").asInt()).isEqualTo(2); // booking b1 (overlap) + contact c9; "old" is outside the window
        assertThat(third.get("recordsAdded").asInt()).isEqualTo(1);
        assertThat(businessRepository.findAll().stream().map(Business::getExternalId)).doesNotContain("booking:old");
    }

    @Test
    void theWebsiteDatabaseIsNeverWrittenTo() throws Exception {
        insertBooking("b1", "Marie", "Tremblay", "514-555-0201", null, null, "Montréal", null,
                "house", null, "Ménage", null, null, null, null, "100", null, "fr", minutesAgo(30));
        runImport();
        try (Connection c = DriverManager.getConnection(WEBSITE_URL, "sa", ""); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT COUNT(*) FROM booking_requests");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            // no column was added to record the import
            var meta = s.executeQuery("SELECT * FROM booking_requests").getMetaData();
            assertThat(meta.getColumnCount()).isEqualTo(19);
        }
    }

    @Test
    void pollerStartsAnImportOnlyWhenTheSourceIsActive() throws Exception {
        insertContact("c1", "Poll Contact", "514-555-0210", null, "Bureau", null, null, "Salut", false, minutesAgo(2));
        source.setActive(false);
        dataSourceRepository.save(source);
        poller.poll();
        assertThat(scraperJobRepository.count()).isZero();

        source.setActive(true);
        dataSourceRepository.save(source);
        poller.poll();
        awaitJobs(1);
        assertThat(businessRepository.findAll().stream().map(Business::getExternalId)).contains("contact:c1");
    }

    @Test
    void manualImportRequiresAnAdminAndViewersAreRefused() throws Exception {
        mvc.perform(post("/api/data-sources/{id}/import", source.getId()).with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/data-sources/{id}/import", source.getId()).with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/data-sources/{id}/import", source.getId()).with(ADMIN)).andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode runImport() throws Exception {
        String body = mvc.perform(post("/api/data-sources/{id}/import", source.getId()).with(ADMIN))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String jobId = json.readTree(body).get("id").asText();
        for (int i = 0; i < 100; i++) {
            JsonNode job = json.readTree(mvc.perform(get("/api/scraper-jobs/{id}", jobId).with(ADMIN)).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8));
            String status = job.get("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                assertThat(status).as(job.toString()).isEqualTo("COMPLETED");
                // lastSync is stamped after the job completes: give it a moment
                Thread.sleep(150);
                source = dataSourceRepository.findById(source.getId()).orElseThrow();
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("import did not finish");
    }

    private void awaitJobs(int count) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (scraperJobRepository.count() >= count
                    && scraperJobRepository.findAll().stream().allMatch(j -> j.getStatus() == com.econet.leads.model.ScraperJob.JobStatus.COMPLETED)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job did not complete");
    }

    private Business byExternalId(String externalId) {
        return businessRepository.findAll().stream().filter(b -> externalId.equals(b.getExternalId())).findFirst().orElseThrow();
    }

    private static LocalDateTime minutesAgo(int m) {
        return LocalDateTime.now().minusMinutes(m);
    }

    private void insertBooking(String id, String first, String last, String phone, String email, String address, String city,
                               String postal, String propertyType, String size, String service, String date, String time,
                               String frequency, String addons, String total, String instructions, String language,
                               LocalDateTime createdAt) throws Exception {
        try (Connection c = DriverManager.getConnection(WEBSITE_URL, "sa", "");
             var ps = c.prepareStatement("INSERT INTO booking_requests VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            Object[] v = {id, first, last, phone, email, address, city, postal, propertyType, size, service,
                    date == null ? null : java.sql.Date.valueOf(date), time, frequency, addons,
                    total == null ? null : new java.math.BigDecimal(total), instructions, language, java.sql.Timestamp.valueOf(createdAt)};
            for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
            ps.executeUpdate();
        }
    }

    private void insertContact(String id, String name, String phone, String email, String service, String size, String date,
                               String message, boolean urgent, LocalDateTime createdAt) throws Exception {
        try (Connection c = DriverManager.getConnection(WEBSITE_URL, "sa", "");
             var ps = c.prepareStatement("INSERT INTO contact_submissions VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            Object[] v = {id, name, phone, email, service, size, date, message, urgent, java.sql.Timestamp.valueOf(createdAt)};
            for (int i = 0; i < v.length; i++) ps.setObject(i + 1, v[i]);
            ps.executeUpdate();
        }
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
