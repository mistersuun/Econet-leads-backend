package com.econet.leads.clients;

import com.econet.leads.config.TimeConfig;
import com.econet.leads.model.Business;
import com.econet.leads.model.Client;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ClientRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Clients after the sale: creation on WON, API, permissions, review requests, MRR and notifications. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.mail.host=smtp.test.invalid",
        "app.mail.username=ventes@example.test",
        "app.reviews.google-url=https://g.page/r/econet-test/review"
})
class ClientsApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    User alice;
    User bob;
    LocalDate today;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");
    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @BeforeEach
    void setUp() {
        cleanup();
        alice = ensureUser("alice", User.UserRole.USER);
        bob = ensureUser("bob", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);
        ensureUser("admin", User.UserRole.ADMIN);
        today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        given(mailSender.createMimeMessage()).willAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TABLE IF EXISTS quotes");
        clientRepository.deleteAll();
        contactRepository.deleteAll();
        businessRepository.deleteAll();
    }

    // ------------------------------------------------------------------ creation on WON

    @Test
    void statusChangeToWonCreatesTheClientOnceFromTheQualification() throws Exception {
        Business lead = lead("CPE Soleil", LeadStatus.QUOTE_SENT, Map.of("frequencyWanted", "bi-weekly"), "250.00", null);

        mvc.perform(patch("/api/businesses/{id}/status", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONTACTED\"}"))
                .andExpect(status().isOk());
        assertThat(clientRepository.count()).isZero();

        mvc.perform(patch("/api/businesses/{id}/status", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WON\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/businesses/{id}/status", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WON\",\"note\":\"Signé\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/clients").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessId").value(lead.getId().toString()))
                .andExpect(jsonPath("$[0].businessName").value("CPE Soleil"))
                .andExpect(jsonPath("$[0].frequency").value("bi-weekly"))
                .andExpect(jsonPath("$[0].pricePerVisit").value(250.0))
                .andExpect(jsonPath("$[0].monthlyValue").value(541.67))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].startDate").value(today.toString()))
                .andExpect(jsonPath("$[0].service").value("Entretien ménager"));
    }

    @Test
    void loggingAWonCallCreatesTheClientAndDefaultsToOneTimeWithoutData() throws Exception {
        Business lead = lead("Clinique Nord", LeadStatus.INTERESTED, null, null, null);
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"WON\"}"))
                .andExpect(status().isOk());

        List<Client> clients = clientRepository.findAll();
        assertThat(clients).hasSize(1);
        assertThat(clients.get(0).getFrequency()).isEqualTo("one-time");
        assertThat(clients.get(0).getPricePerVisit()).isEqualByComparingTo("0");
    }

    @Test
    void acceptedQuoteProvidesServiceFrequencyAndPriceWhenTheQuotesTableExists() throws Exception {
        jdbc.execute("CREATE TABLE quotes (id UUID PRIMARY KEY, business_id UUID NOT NULL, status VARCHAR(20), "
                + "input VARCHAR(2000), subtotal NUMERIC(10,2), accepted_at TIMESTAMP)");
        Business lead = lead("Bureau Alpha", LeadStatus.QUOTE_SENT, Map.of("frequencyWanted", "monthly"), "999.00", null);
        jdbc.update("INSERT INTO quotes VALUES (?, ?, 'declined', ?, 50, NULL)", UUID.randomUUID(), lead.getId(),
                "{\"service\":\"deep_cleaning\",\"frequency\":\"one-time\"}");
        jdbc.update("INSERT INTO quotes VALUES (?, ?, 'accepted', ?, 312.50, ?)", UUID.randomUUID(), lead.getId(),
                "{\"service\":\"commercial\",\"frequency\":\"weekly\"}", LocalDateTime.now());

        mvc.perform(patch("/api/businesses/{id}/status", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WON\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/clients").with(ALICE))
                .andExpect(jsonPath("$[0].service").value("Entretien commercial"))
                .andExpect(jsonPath("$[0].frequency").value("weekly"))
                .andExpect(jsonPath("$[0].pricePerVisit").value(312.5));
    }

    // ------------------------------------------------------------------ API and permissions

    @Test
    void listFilterGetPatchAndPermissions() throws Exception {
        Client active = client("Resto Un", "weekly", "100", "active", null, null, null);
        Client paused = client("Resto Deux", "monthly", "80", "paused", null, null, null);

        mvc.perform(get("/api/clients").param("status", "paused").with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(paused.getId().toString()));
        mvc.perform(get("/api/clients").param("status", "bogus").with(ALICE)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/clients").param("businessId", active.getBusiness().getId().toString()).with(ALICE))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/clients/{id}", UUID.randomUUID()).with(ALICE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/clients/{id}", active.getId()).with(VIEWER)).andExpect(status().isOk());
        mvc.perform(get("/api/clients")).andExpect(status().isUnauthorized());

        String body = "{\"pricePerVisit\":135.5,\"frequency\":\"bi-weekly\",\"contractEndDate\":\"" + today.plusDays(20)
                + "\",\"nextVisitAt\":\"" + today.plusDays(2) + "T09:30:00\",\"notes\":\"Clé au concierge\",\"service\":\"Grand ménage\"}";
        mvc.perform(patch("/api/clients/{id}", active.getId()).with(VIEWER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/clients/{id}", active.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerVisit").value(135.5))
                .andExpect(jsonPath("$.frequency").value("bi-weekly"))
                .andExpect(jsonPath("$.contractEndDate").value(today.plusDays(20).toString()))
                .andExpect(jsonPath("$.nextVisitAt").value(today.plusDays(2) + "T09:30:00"))
                .andExpect(jsonPath("$.notes").value("Clé au concierge"))
                .andExpect(jsonPath("$.service").value("Grand ménage"));
        // null clears the optional dates
        mvc.perform(patch("/api/clients/{id}", active.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractEndDate\":null,\"nextVisitAt\":null,\"status\":\"ended\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractEndDate").doesNotExist())
                .andExpect(jsonPath("$.status").value("ended"));

        for (String bad : List.of("{\"frequency\":\"daily\"}", "{\"pricePerVisit\":-1}", "{\"pricePerVisit\":\"abc\"}",
                "{\"status\":\"gone\"}", "{\"startDate\":\"15/01/2026\"}", "{\"service\":\" \"}", "{\"color\":\"red\"}")) {
            mvc.perform(patch("/api/clients/{id}", active.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }

        mvc.perform(delete("/api/clients/{id}", paused.getId()).with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/clients/{id}", paused.getId()).with(ADMIN)).andExpect(status().isNoContent());
        assertThat(clientRepository.existsById(paused.getId())).isFalse();
        assertThat(businessRepository.existsById(paused.getBusiness().getId())).isTrue();
    }

    @Test
    void firstCleaningDoneIsRecordedOnceWithAHistoryNote() throws Exception {
        Client c = client("Resto Trois", "weekly", "90", "active", null, null, null);

        mvc.perform(post("/api/clients/{id}/first-cleaning-done", c.getId()).with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/clients/{id}/first-cleaning-done", c.getId()).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstCleaningDoneAt").exists());
        LocalDateTime first = clientRepository.findById(c.getId()).orElseThrow().getFirstCleaningDoneAt();
        mvc.perform(post("/api/clients/{id}/first-cleaning-done", c.getId()).with(ALICE)).andExpect(status().isOk());

        assertThat(clientRepository.findById(c.getId()).orElseThrow().getFirstCleaningDoneAt()).isEqualTo(first);
        assertThat(contactRepository.findAll()).filteredOn(x -> "CLIENT_FIRST_CLEANING".equals(x.getContactStatus())).hasSize(1);
    }

    // ------------------------------------------------------------------ Google review request

    @Test
    void reviewRequestByLinkReturnsTheMessageAndRecordsTheDate() throws Exception {
        Client c = client("Resto Quatre", "weekly", "90", "active", null, null, null);

        mvc.perform(get("/api/clients/review-config").with(ALICE))
                .andExpect(jsonPath("$.googleReviewUrl").value(true))
                .andExpect(jsonPath("$.smtp").value(true));
        mvc.perform(post("/api/clients/{id}/review-request", c.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"fax\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/clients/{id}/review-request", c.getId()).with(VIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"link\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/clients/{id}/review-request", c.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"link\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://g.page/r/econet-test/review"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("https://g.page/r/econet-test/review")));

        assertThat(clientRepository.findById(c.getId()).orElseThrow().getReviewRequestedAt()).isNotNull();
    }

    @Test
    void reviewRequestByEmailSendsAndLogsOrExplainsWhatIsMissing() throws Exception {
        Client c = client("Resto Cinq", "weekly", "90", "active", null, null, null);
        c.getBusiness().setEmail(null);
        businessRepository.save(c.getBusiness());

        mvc.perform(post("/api/clients/{id}/review-request", c.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"email\"}"))
                .andExpect(status().isBadRequest());
        assertThat(clientRepository.findById(c.getId()).orElseThrow().getReviewRequestedAt()).isNull();

        c.getBusiness().setEmail("resto5@example.com");
        businessRepository.save(c.getBusiness());
        mvc.perform(post("/api/clients/{id}/review-request", c.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("email"));

        verify(mailSender).send(any(MimeMessage.class));
        assertThat(clientRepository.findById(c.getId()).orElseThrow().getReviewRequestedAt()).isNotNull();
        assertThat(contactRepository.findAll()).filteredOn(x -> x.getContactType() == Contact.ContactType.EMAIL).hasSize(1);
    }

    // ------------------------------------------------------------------ dashboard and notifications

    @Test
    void dashboardSummaryComputesMrrFromActiveClientsOnly() throws Exception {
        client("Resto A", "weekly", "100", "active", today.plusDays(10), null, null);
        client("Resto B", "bi-weekly", "100", "active", today.plusDays(45), null, today.minusDays(8).atStartOfDay());
        client("Resto C", "monthly", "100", "active", null, null, today.minusDays(2).atStartOfDay());
        client("Resto D", "one-time", "500", "active", null, null, null);
        client("Resto E", "weekly", "999", "paused", today.plusDays(5), null, null);

        mvc.perform(get("/api/dashboard/clients").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(4))
                .andExpect(jsonPath("$.monthlyRecurringRevenue").value(750.0))
                .andExpect(jsonPath("$.renewalsNext30Days").value(1))
                .andExpect(jsonPath("$.reviewsPending").value(2));
        mvc.perform(get("/api/dashboard/clients").with(VIEWER)).andExpect(status().isOk());
    }

    @Test
    void notificationsListRenewalsReviewsAndVisitsOfTheUsersClients() throws Exception {
        Client renewal = client("Resto Renouvel", "weekly", "100", "active", today.plusDays(12), null, null);
        Client review = client("Resto Avis", "weekly", "100", "active", null, null, LocalDateTime.now().minusDays(8));
        Client visit = client("Resto Visite", "weekly", "100", "active", null, today.atTime(23, 0), null);
        Client early = client("Resto Trop Tôt", "weekly", "100", "active", today.plusDays(40), today.plusDays(1).atTime(9, 0), LocalDateTime.now().minusDays(3));
        Client asked = client("Resto Déjà Demandé", "weekly", "100", "active", null, null, LocalDateTime.now().minusDays(20));
        asked.setReviewRequestedAt(LocalDateTime.now().minusDays(2));
        clientRepository.save(asked);
        Client paused = client("Resto Pause", "weekly", "100", "paused", today.plusDays(3), null, null);
        Client others = client("Resto De Bob", "weekly", "100", "active", today.plusDays(4), null, null);
        others.getBusiness().setAssignedTo(bob);
        businessRepository.save(others.getBusiness());

        var result = mvc.perform(get("/api/notifications").with(ALICE)).andExpect(status().isOk());
        result.andExpect(jsonPath("$[?(@.id=='contract_renewal:" + renewal.getId() + "')].kind").value(hasItem("contract_renewal")))
                .andExpect(jsonPath("$[?(@.id=='contract_renewal:" + renewal.getId() + "')].leadId").value(hasItem(renewal.getBusiness().getId().toString())))
                .andExpect(jsonPath("$[?(@.id=='review_request_due:" + review.getId() + "')].kind").value(hasItem("review_request_due")))
                .andExpect(jsonPath("$[?(@.id=='visit_due:" + visit.getId() + "')].kind").value(hasItem("visit_due")))
                .andExpect(jsonPath("$[?(@.id=='contract_renewal:" + early.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='visit_due:" + early.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='review_request_due:" + early.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='review_request_due:" + asked.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='contract_renewal:" + paused.getId() + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id=='contract_renewal:" + others.getId() + "')]").isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private User ensureUser(String username, User.UserRole role) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + username + "-" + UUID.randomUUID()));
            u.setRole(role);
            u.setActive(true);
            return userRepository.save(u);
        });
    }

    private Business lead(String name, LeadStatus status, Map<String, Object> qualification, String value, User assignee) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("CPE");
        b.setAddressCity("Montréal");
        b.setPhone("5145550100");
        b.setPhoneNormalized("5145550100");
        b.setDataSource("TEST");
        b.setLeadStatus(status);
        b.setQualification(qualification == null ? null : new java.util.LinkedHashMap<>(qualification));
        b.setEstimatedValue(value == null ? null : new BigDecimal(value));
        b.setAssignedTo(assignee);
        return businessRepository.save(b);
    }

    private Client client(String name, String frequency, String price, String status, LocalDate contractEnd,
                          LocalDateTime nextVisit, LocalDateTime firstCleaning) {
        Business b = lead(name, LeadStatus.WON, null, null, null);
        Client c = new Client();
        c.setBusiness(b);
        c.setService("Entretien ménager");
        c.setFrequency(frequency);
        c.setPricePerVisit(new BigDecimal(price));
        c.setStartDate(today);
        c.setContractEndDate(contractEnd);
        c.setNextVisitAt(nextVisit);
        c.setStatus(status);
        c.setFirstCleaningDoneAt(firstCleaning);
        return clientRepository.save(c);
    }
}
