package com.econet.leads.controller;

import com.econet.leads.model.Business;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests of the CRM endpoints on H2 (PostgreSQL mode). Native dashboard SQL is plain
 * ANSI so it is exercised here too; the real migration is verified separately against PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrmApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    User alice;
    User bob;
    Business followUpDue;
    Business newHighQuality;
    Business newLowQuality;
    Business newNoPhone;
    Business wonLead;
    Business bobsLead;

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        alice = ensureUser("alice", User.UserRole.USER);
        bob = ensureUser("bob", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);

        LocalDateTime now = LocalDateTime.now();
        followUpDue = lead("Clinique Due", "Montréal", "5145550001", 10, LeadStatus.CONTACTED, null, now.minusHours(2));
        newHighQuality = lead("CPE Haute Qualité", "Laval", "4505550002", 90, LeadStatus.NEW, null, null);
        newLowQuality = lead("CPE Basse Qualité", "Laval", "4505550003", 20, LeadStatus.NEW, null, null);
        newNoPhone = lead("Sans Téléphone", "Québec", null, 95, LeadStatus.NEW, null, null);
        wonLead = lead("Client Gagné", "Québec", "4185550004", 80, LeadStatus.WON, null, now.minusDays(1));
        bobsLead = lead("Lead De Bob", "Montréal", "5145550005", 99, LeadStatus.NEW, bob, null);
    }

    private User ensureUser(String username, User.UserRole role) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@example.com");
            u.setPasswordHash(passwordEncoder.encode("password-" + username));
            u.setRole(role);
            u.setActive(true);
            return userRepository.save(u);
        });
    }

    private Business lead(String name, String city, String phone, int quality, LeadStatus status, User assignee,
                          LocalDateTime followUp) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("CPE");
        b.setAddressCity(city);
        b.setPhone(phone);
        b.setPhoneNormalized(phone);
        b.setDataSource("TEST");
        b.setDataQualityScore(quality);
        b.setLeadStatus(status);
        b.setAssignedTo(assignee);
        b.setNextFollowUpAt(followUp);
        return businessRepository.save(b);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void queueOrdersFollowUpsThenNewLeadsByQuality() throws Exception {
        mvc.perform(get("/api/leads/queue").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].businessName").value("Clinique Due"))
                .andExpect(jsonPath("$[1].businessName").value("CPE Haute Qualité"))
                .andExpect(jsonPath("$[2].businessName").value("CPE Basse Qualité"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void loggingACallUpdatesTheLead() throws Exception {
        mvc.perform(post("/api/leads/{id}/calls", newHighQuality.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"INTERESTED\",\"notes\":\"Rappeler la directrice\",\"estimatedValue\":3200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lead.leadStatus").value("INTERESTED"))
                .andExpect(jsonPath("$.lead.contactCount").value(1))
                .andExpect(jsonPath("$.lead.assignedToName").value("alice"))
                .andExpect(jsonPath("$.lead.estimatedValue").value(3200))
                .andExpect(jsonPath("$.lead.nextFollowUpAt", endsWith("T10:00:00")))
                .andExpect(jsonPath("$.contact.contactType").value("APPEL"))
                .andExpect(jsonPath("$.contact.outcome").value("INTERESTED"))
                .andExpect(jsonPath("$.contact.username").value("alice"));

        mvc.perform(get("/api/contacts/business/{id}", newHighQuality.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(1))
                .andExpect(jsonPath("$.callsToday").value(1))
                .andExpect(jsonPath("$.conversations").value(1))
                .andExpect(jsonPath("$.pipelineValue").value(3200.0))
                .andExpect(jsonPath("$.totalLeads").value(6))
                .andExpect(jsonPath("$.callableLeads").value(4))
                .andExpect(jsonPath("$.followUpsOverdue").value(anyOf(is(0), is(1))));

        mvc.perform(get("/api/dashboard/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)))
                .andExpect(jsonPath("$[29].calls").value(1))
                .andExpect(jsonPath("$[29].conversations").value(1));

        mvc.perform(get("/api/dashboard/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].calls").value(1));

        mvc.perform(get("/api/dashboard/outcomes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(9)))
                .andExpect(jsonPath("$[0].outcome").value("INTERESTED"))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    @WithMockUser(username = "viewer", roles = "VIEWER")
    void viewerCanReadButNotWrite() throws Exception {
        mvc.perform(get("/api/leads/queue")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/pipeline")).andExpect(status().isOk());
        mvc.perform(post("/api/leads/{id}/calls", newHighQuality.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NO_ANSWER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(patch("/api/businesses/{id}/status", newHighQuality.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOST\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void registerIsAdminOnly() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"email\":\"m@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401WithJsonError() throws Exception {
        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"email\":\"m@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void meReturnsCurrentUser() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.userId").value(alice.getId().toString()));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void patchStatusAndPipeline() throws Exception {
        mvc.perform(patch("/api/businesses/{id}/status", newLowQuality.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUOTE_SENT\",\"note\":\"Soumission envoyée par courriel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadStatus").value("QUOTE_SENT"));

        mvc.perform(patch("/api/businesses/{id}/status", newLowQuality.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(get("/api/dashboard/pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[3].status").value("QUOTE_SENT"))
                .andExpect(jsonPath("$[3].count").value(1))
                .andExpect(jsonPath("$[6].status").value("DO_NOT_CALL"))
                .andExpect(jsonPath("$[6].count").value(0));

        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(jsonPath("$.quotesSent").value(1));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void listFiltersAndSorting() throws Exception {
        mvc.perform(get("/api/businesses").param("leadStatus", "NEW,WON").param("sortBy", "dataQualityScore")
                        .param("sortDirection", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[0].businessName").value("Lead De Bob"));

        mvc.perform(get("/api/businesses").param("leadStatus", "NEW").param("leadStatus", "CONTACTED")
                        .param("hasPhone", "true"))
                .andExpect(jsonPath("$.totalElements").value(4));

        mvc.perform(get("/api/businesses").param("q", "laval"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/businesses").param("q", "555-0005"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/businesses").param("followUpDue", "true"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/businesses").param("assignedTo", "me"))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/businesses").param("assignedTo", bob.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/businesses").param("city", "québec"))
                .andExpect(jsonPath("$.totalElements").value(2));

        mvc.perform(get("/api/businesses").param("sortBy", "passwordHash"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("sortBy")));

        mvc.perform(get("/api/businesses/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessTypes", contains("CPE")))
                .andExpect(jsonPath("$.cities", contains("Laval", "Montréal", "Québec")))
                .andExpect(jsonPath("$.dataSources", contains("TEST")));

        mvc.perform(get("/api/dashboard/breakdown").param("dimension", "city").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/dashboard/breakdown").param("dimension", "nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void csvExportHasBomHeaderAndAccents() throws Exception {
        MvcResult result = mvc.perform(get("/api/businesses/export.csv").param("city", "Laval")
                        .param("sortBy", "businessName").param("sortDirection", "ASC"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andReturn();
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String csv = new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("name,type,phone,email,website,street,city,postal_code,status,last_contacted,next_follow_up,quality,source");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith("\"CPE Basse Qualité\"");
        assertThat(lines[2]).startsWith("\"CPE Haute Qualité\"");
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void putAcceptsEstimatedValueAndAssignee() throws Exception {
        mvc.perform(put("/api/businesses/{id}", newLowQuality.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimatedValue\": 1234.5, \"assignedToId\": \"" + alice.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedValue").value(1234.5))
                .andExpect(jsonPath("$.assignedToId").value(alice.getId().toString()));
        assertThat(businessRepository.findById(newLowQuality.getId()).orElseThrow().getEstimatedValue())
                .isEqualByComparingTo(new BigDecimal("1234.50"));
    }
}
