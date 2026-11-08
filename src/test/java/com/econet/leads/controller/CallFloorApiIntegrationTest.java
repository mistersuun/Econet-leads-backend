package com.econet.leads.controller;

import com.econet.leads.model.Business;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.CallScript;
import com.econet.leads.model.Contact;
import com.econet.leads.model.EmailTemplate;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.ScriptSection;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.econet.leads.model.TenderStatus;
import com.econet.leads.model.User;
import com.econet.leads.realtime.ClaimRegistry;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.CallScriptRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.EmailTemplateRepository;
import com.econet.leads.repository.TenderRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.security.JwtTokenProvider;
import com.econet.leads.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Addendum 3 ("the living call floor") end to end on H2: SSE auth and events, claims, calls with
 * duration / qualification / snooze, learned queue priority and filters, scripts, emails (mock
 * SMTP) with ICS and rate limit, scoreboard and insight endpoints, notifications, VIEWER 403s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.mail.host=smtp.test.invalid",
        "app.mail.username=ventes@example.test",
        "app.mail.rate-limit-per-hour=2"
})
class CallFloorApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired TenderRepository tenderRepository;
    @Autowired CallScriptRepository scriptRepository;
    @Autowired EmailTemplateRepository templateRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired ClaimRegistry claimRegistry;
    @Autowired SseEventService sseEventService;
    @Autowired CacheManager cacheManager;
    @MockitoBean JavaMailSender mailSender;

    User alice;
    User bob;
    User carol;
    LocalDate today;
    LocalDateTime now;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor BOB = user("bob").roles("USER");
    static final RequestPostProcessor CAROL = user("carol").roles("USER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");
    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        tenderRepository.deleteAll();
        scriptRepository.deleteAll();
        templateRepository.deleteAll();
        claimRegistry.clearAll();
        cacheManager.getCache(DashboardService.CONVERSION_CACHE).clear();
        alice = ensureUser("alice", User.UserRole.USER);
        bob = ensureUser("bob", User.UserRole.USER);
        carol = ensureUser("carol", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);
        now = LocalDateTime.now();
        today = now.toLocalDate();
        given(mailSender.createMimeMessage()).willAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    // ------------------------------------------------------------------ SSE

    @Test
    void sseNeedsTheTokenAsQueryParamAndStreamsPresenceAndEvents() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/events").param("token", "not-a-jwt")).andExpect(status().isUnauthorized());

        String token = jwtTokenProvider.generateToken("alice");
        // the query token is accepted on /api/events only
        mvc.perform(get("/api/dashboard/pipeline").param("token", token)).andExpect(status().isUnauthorized());

        MvcResult stream = mvc.perform(get("/api/events").param("token", token).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
        String body = stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:presence").contains("\"username\":\"alice\"").contains("\"status\":\"idle\"");

        Business lead = lead("CPE Flux", "Montréal", "5145550010", 70, LeadStatus.NEW, null, null);
        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(BOB)).andExpect(status().isOk());
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(BOB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"INTERESTED\",\"durationSeconds\":125}"))
                .andExpect(status().isOk());
        sseEventService.heartbeat();

        body = stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:lead.claimed").contains("\"leadName\":\"CPE Flux\"")
                .contains("\"status\":\"calling\"")
                .contains("event:lead.released")
                .contains("event:call.logged").contains("\"durationSeconds\":125")
                .contains("event:lead.updated").contains("\"leadStatus\":\"INTERESTED\"")
                .contains("event:target.progress").contains("\"callsToday\":1")
                .contains(":heartbeat");
        assertThat(body).matches("(?s).*id:\\d+\\s*\\nevent:lead\\.claimed.*");

        // reconnect with Last-Event-ID replays what was missed (ring of 200)
        MvcResult replay = mvc.perform(get("/api/events").param("token", token).header("Last-Event-ID", "0"))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("event:lead.claimed").contains("event:call.logged").contains("event:presence");
        assertThat(sseEventService.openConnections()).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ claims

    @Test
    void claimsConflictAtMostOnceAndHideLeadsFromOtherQueues() throws Exception {
        Business lead = lead("Clinique Réservée", "Laval", "4505550011", 80, LeadStatus.NEW, null, null);

        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadId").value(lead.getId().toString()))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.userId").value(alice.getId().toString()))
                .andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(ALICE)).andExpect(status().isOk());

        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(BOB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.claimedBy.username").value("alice"))
                .andExpect(jsonPath("$.claimedBy.userId").value(alice.getId().toString()))
                .andExpect(jsonPath("$.expiresAt").exists());

        mvc.perform(get("/api/leads/queue").with(BOB)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/leads/queue").with(ALICE)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].claimedBy.username").value("alice"))
                .andExpect(jsonPath("$[0].priorityScore").isNumber());

        // bob cannot release alice's claim; alice can
        mvc.perform(delete("/api/leads/{id}/claim", lead.getId()).with(BOB)).andExpect(status().isNoContent());
        mvc.perform(get("/api/leads/queue").with(BOB)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete("/api/leads/{id}/claim", lead.getId()).with(ALICE)).andExpect(status().isNoContent());
        mvc.perform(get("/api/leads/queue").with(BOB)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].claimedBy").value(nullValue()));

        // logging a call releases automatically
        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(ALICE)).andExpect(status().isOk());
        assertThat(claimRegistry.get(lead.getId())).isPresent();
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"NO_ANSWER\"}"))
                .andExpect(status().isOk());
        assertThat(claimRegistry.get(lead.getId())).isEmpty();

        mvc.perform(post("/api/leads/{id}/claim", UUID.randomUUID()).with(ALICE)).andExpect(status().isNotFound());
        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/leads/{id}/claim", lead.getId()).with(VIEWER)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ calls: duration, qualification, snooze

    @Test
    void callsCarryDurationQualificationAndSnoozeUntil() throws Exception {
        Business lead = lead("CPE Qualifié", "Montréal", "5145550012", 70, LeadStatus.NEW, null, null);
        LocalDateTime snoozeUntil = today.plusDays(1).atTime(9, 0);

        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "outcome", "NO_ANSWER",
                                "durationSeconds", 42,
                                "snoozeUntil", snoozeUntil.toString(),
                                "qualification", Map.of("decisionMaker", "Mme Roy", "frequencyWanted", "weekly",
                                        "painPoints", List.of("prix", "qualité"), "sizeSqft", 2500)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.durationSeconds").value(42))
                .andExpect(jsonPath("$.lead.leadStatus").value("CONTACTED"))
                .andExpect(jsonPath("$.lead.nextFollowUpAt").value(startsWith(snoozeUntil.toString())))
                .andExpect(jsonPath("$.lead.qualification.decisionMaker").value("Mme Roy"))
                .andExpect(jsonPath("$.lead.qualification.painPoints", contains("prix", "qualité")))
                .andExpect(jsonPath("$.lead.priorityScore").isNumber());

        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"INTERESTED\",\"snoozeUntil\":\"" + snoozeUntil + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("snoozeUntil")));

        // PATCH merges: new keys added, null clears, others untouched
        mvc.perform(patch("/api/businesses/{id}/qualification", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"roy@example.com\",\"employees\":12,\"frequencyWanted\":null,\"contractEndsAt\":\"2027-03-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionMaker").value("Mme Roy"))
                .andExpect(jsonPath("$.email").value("roy@example.com"))
                .andExpect(jsonPath("$.employees").value(12))
                .andExpect(jsonPath("$.contractEndsAt").value("2027-03-31"))
                .andExpect(jsonPath("$.frequencyWanted").doesNotExist());
        mvc.perform(get("/api/businesses/{id}/qualification", lead.getId()).with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeSqft").value(2500))
                .andExpect(jsonPath("$.email").value("roy@example.com"));
        mvc.perform(get("/api/businesses/{id}", lead.getId()).with(ALICE))
                .andExpect(jsonPath("$.qualification.decisionMaker").value("Mme Roy"))
                .andExpect(jsonPath("$.priorityScore").isNumber());

        mvc.perform(patch("/api/businesses/{id}/qualification", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frequencyWanted\":\"daily\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/businesses/{id}/qualification", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favouriteColour\":\"vert\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("favouriteColour")));
        mvc.perform(patch("/api/businesses/{id}/qualification", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contractEndsAt\":\"31/03/2027\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/businesses/{id}/qualification", lead.getId()).with(VIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"))
                .andExpect(status().isForbidden());

        // CSV export carries the qualification columns
        String csv = mvc.perform(get("/api/businesses/export.csv").with(ALICE)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("decision_maker", "current_provider", "contract_ends_at", "employees")
                .contains("\"Mme Roy\"").contains("\"2027-03-31\"").contains("\"12\"");

        // the call history shows the duration
        mvc.perform(get("/api/contacts/business/{id}", lead.getId()).with(BOB))
                .andExpect(jsonPath("$[0].durationSeconds").value(42));
    }

    @Test
    void snoozePushesTheFollowUpWritesANoteAndReleasesTheClaim() throws Exception {
        Business lead = lead("Resto Reporté", "Québec", "4185550013", 60, LeadStatus.CONTACTED, alice, now.minusHours(1));
        Business won = lead("Client Gagné", "Québec", "4185550014", 60, LeadStatus.WON, alice, null);
        LocalDateTime until = today.plusDays(3).atTime(10, 0);

        mvc.perform(post("/api/leads/{id}/claim", lead.getId()).with(ALICE)).andExpect(status().isOk());
        mvc.perform(post("/api/leads/{id}/snooze", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"" + until + "\",\"reason\":\"Responsable en vacances\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadStatus").value("CONTACTED"))
                .andExpect(jsonPath("$.nextFollowUpAt").value(startsWith(until.toString())))
                .andExpect(jsonPath("$.claimedBy").value(nullValue()));
        assertThat(claimRegistry.get(lead.getId())).isEmpty();
        mvc.perform(get("/api/contacts/business/{id}", lead.getId()).with(ALICE))
                .andExpect(jsonPath("$[0].contactType").value("NOTE"))
                .andExpect(jsonPath("$[0].notes", startsWith("Reporté au ")))
                .andExpect(jsonPath("$[0].notes", containsString("Responsable en vacances")))
                .andExpect(jsonPath("$[0].nextActionDate").value(startsWith(until.toString())));
        // no longer in the queue (follow-up in the future)
        mvc.perform(get("/api/leads/queue").with(ALICE)).andExpect(jsonPath("$[?(@.businessName == 'Resto Reporté')]", hasSize(0)));

        mvc.perform(post("/api/leads/{id}/snooze", won.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + until + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("WON")));
        mvc.perform(post("/api/leads/{id}/snooze", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + now.minusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/snooze", lead.getId()).with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.until").exists());
        mvc.perform(post("/api/leads/{id}/snooze", lead.getId()).with(VIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + until + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ queue priority and filters

    @Test
    void queueRanksNewLeadsByLearnedPriority() throws Exception {
        // 10 restaurants called and lost, 5 CPEs called and won, in the last 180 days
        for (int i = 0; i < 10; i++) {
            Business b = lead("Resto Perdu " + i, "Montréal", "514555" + String.format("%04d", 100 + i), 50, LeadStatus.LOST, bob, null);
            call(b, bob, CallOutcome.NOT_INTERESTED, now.minusDays(5 + i), 60);
        }
        for (int i = 0; i < 5; i++) {
            Business b = lead("CPE Gagné " + i, "Montréal", "514555" + String.format("%04d", 200 + i), 50, LeadStatus.WON, bob, null);
            call(b, bob, CallOutcome.WON, now.minusDays(3 + i), 300);
        }
        Business resto = lead("Resto Nouveau", "Laval", "4505550300", 90, LeadStatus.NEW, null, null);
        Business cpe = lead("CPE Nouveau", "Laval", "4505550301", 60, LeadStatus.NEW, null, null);
        cpe.setBusinessType("CPE");
        resto.setBusinessType("Restaurant");
        businessRepository.saveAll(List.of(cpe, resto));
        cacheManager.getCache(DashboardService.CONVERSION_CACHE).clear();

        // CPE: rate (5+2)/(5+10) = 0.4667 -> 0.5*60 + 50*0.4667 = 53.3 ; Restaurant: (0+2)/(10+10) = 0.1 -> 45 + 5 = 50
        mvc.perform(get("/api/leads/queue").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].businessName").value("CPE Nouveau"))
                .andExpect(jsonPath("$[0].priorityScore").value(53.3))
                .andExpect(jsonPath("$[1].businessName").value("Resto Nouveau"))
                .andExpect(jsonPath("$[1].priorityScore").value(50.0));

        mvc.perform(get("/api/dashboard/conversion-by-type").with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].businessType").value("CPE"))
                .andExpect(jsonPath("$[0].leadsCalled").value(5))
                .andExpect(jsonPath("$[0].conversations").value(5))
                .andExpect(jsonPath("$[0].positive").value(5))
                .andExpect(jsonPath("$[0].rate").value(closeTo(0.4667, 0.001)))
                .andExpect(jsonPath("$[1].businessType").value("Restaurant"))
                .andExpect(jsonPath("$[1].leadsCalled").value(10))
                .andExpect(jsonPath("$[1].conversations").value(10))
                .andExpect(jsonPath("$[1].positive").value(0))
                .andExpect(jsonPath("$[1].rate").value(0.1));

        // the list endpoint exposes the same score
        mvc.perform(get("/api/businesses").with(ALICE).param("q", "CPE Nouveau"))
                .andExpect(jsonPath("$.content[0].priorityScore").value(53.3));
    }

    @Test
    void queueFiltersForSessionMode() throws Exception {
        Business due = lead("Clinique Due", "Montréal", "5145550020", 10, LeadStatus.CONTACTED, null, now.minusHours(2));
        due.setBusinessType("Clinique");
        Business cpe = lead("CPE Laval", "Laval", "4505550021", 90, LeadStatus.NEW, null, null);
        Business resto = lead("Resto Laval", "Laval", "4505550022", 20, LeadStatus.NEW, null, null);
        resto.setBusinessType("Restaurant");
        resto.setDataSource("OTHER");
        businessRepository.saveAll(List.of(due, resto));

        mvc.perform(get("/api/leads/queue").with(ALICE)).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].businessName").value("Clinique Due"));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("newOnly", "true")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].businessName").value("CPE Laval"));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("followUpsOnly", "true")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessName").value("Clinique Due"));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("businessType", "Restaurant")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessName").value("Resto Laval"));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("businessType", "Restaurant").param("businessType", "Clinique"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("city", "laval")).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("sourceIn", "OTHER")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("minQualityScore", "50")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessName").value("CPE Laval"));
        mvc.perform(get("/api/leads/queue").with(ALICE).param("city", "Gatineau")).andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------ scripts

    @Test
    void scriptsBestMatchFallsBackToTheLanguageDefault() throws Exception {
        script("Script général", null, "fr");
        script("Script CPE", "CPE", "fr");
        script("General script", null, "en");

        mvc.perform(get("/api/scripts").with(VIEWER).param("businessType", "CPE").param("language", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Script CPE"))
                .andExpect(jsonPath("$.businessType").value("CPE"))
                .andExpect(jsonPath("$.fallback").value(false))
                .andExpect(jsonPath("$.sections", hasSize(4)))
                .andExpect(jsonPath("$.sections[0].key").value("opening"))
                .andExpect(jsonPath("$.sections[0].body", containsString("{{businessName}}")));
        mvc.perform(get("/api/scripts").with(ALICE).param("businessType", "cpe"))
                .andExpect(jsonPath("$.name").value("Script CPE"));
        mvc.perform(get("/api/scripts").with(ALICE).param("businessType", "Hôtel").param("language", "fr"))
                .andExpect(jsonPath("$.name").value("Script général"))
                .andExpect(jsonPath("$.businessType").value(nullValue()))
                .andExpect(jsonPath("$.fallback").value(true));
        mvc.perform(get("/api/scripts").with(ALICE).param("businessType", "CPE").param("language", "en"))
                .andExpect(jsonPath("$.name").value("General script"))
                .andExpect(jsonPath("$.fallback").value(true));
        mvc.perform(get("/api/scripts").with(ALICE))
                .andExpect(jsonPath("$.name").value("Script général")).andExpect(jsonPath("$.fallback").value(false));
        mvc.perform(get("/api/scripts").with(ALICE).param("language", "de")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/scripts/all").with(ALICE)).andExpect(jsonPath("$", hasSize(3)));

        String upsert = json.writeValueAsString(Map.of("name", "Script Chantier", "businessType", "Chantier", "language", "fr",
                "sections", List.of(Map.of("key", "opening", "title", "Ouverture", "body", "Bonjour {{decisionMaker}}"))));
        mvc.perform(post("/api/scripts").with(ALICE).contentType(MediaType.APPLICATION_JSON).content(upsert))
                .andExpect(status().isForbidden());
        String id = mvc.perform(post("/api/scripts").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(upsert))
                .andExpect(status().isOk()).andExpect(jsonPath("$.businessType").value("Chantier"))
                .andReturn().getResponse().getContentAsString();
        UUID scriptId = UUID.fromString(json.readTree(id).get("id").asText());
        mvc.perform(get("/api/scripts").with(ALICE).param("businessType", "Chantier"))
                .andExpect(jsonPath("$.id").value(scriptId.toString()));
        mvc.perform(put("/api/scripts/{id}", scriptId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "X", "language", "fr",
                                "sections", List.of(Map.of("key", "jokes", "body", "..."))))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/scripts/{id}", scriptId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Script Chantier v2", "businessType", "Chantier", "language", "fr", "active", false,
                                "sections", List.of(Map.of("key", "closing", "title", "Fin", "body", "Merci"))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // inactive scripts are skipped by the best match
        mvc.perform(get("/api/scripts").with(ALICE).param("businessType", "Chantier"))
                .andExpect(jsonPath("$.fallback").value(true));
    }

    // ------------------------------------------------------------------ email

    @Test
    void emailPreviewSendWithIcsAndRateLimit() throws Exception {
        template("quote_followup", "fr", "Votre soumission d'entretien ménager – {{businessName}}",
                "Bonjour {{decisionMaker}},\n\nVoici notre estimation :\n\n{{estimateLines}}\nTotal : {{estimateTotal}}\n\n{{extraNote}}\n{{callerName}}\n{{companyName}}");
        template("visit_confirmation", "fr", "Confirmation de visite – {{businessName}}",
                "Bonjour {{decisionMaker}},\n\nJe vous confirme notre visite à {{businessName}} le {{visitAt}}.\n\n{{callerName}}");
        template("intro", "en", "Cleaning services for {{businessName}}", "Hello {{decisionMaker}},\n\nAs discussed...\n\n{{callerName}}");
        Business lead = lead("CPE Les Lucioles", "Montréal", "5145550030", 80, LeadStatus.INTERESTED, alice, null);
        lead.setAddressStreet("123 Rue Saint-Denis");
        lead.setPostalCode("H2X 1K1");
        lead.setQualification(Map.of("decisionMaker", "Mme Roy", "email", "roy@example.com"));
        businessRepository.save(lead);
        Business noEmail = lead("Sans Courriel", "Laval", "4505550031", 50, LeadStatus.NEW, null, null);

        mvc.perform(get("/api/email/status").with(VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.from").value("ventes@example.test"))
                .andExpect(jsonPath("$.fromName").value("EcoNet Propreté"));

        String quote = json.writeValueAsString(Map.of("templateKey", "quote_followup", "language", "fr",
                "estimate", Map.of("service", "residential", "area", "1500_2500", "propertyType", "house",
                        "addOnIds", List.of("oven", "fridge"), "frequency", "weekly"),
                "extraNote", "Rabais de bienvenue inclus."));
        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON).content(quote))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Votre soumission d'entretien ménager – CPE Les Lucioles"))
                .andExpect(jsonPath("$.body", startsWith("Bonjour Mme Roy,")))
                .andExpect(jsonPath("$.body", containsString("Entretien résidentiel : 207,00 $")))
                .andExpect(jsonPath("$.body", containsString("+ Four : 25,00 $")))
                .andExpect(jsonPath("$.body", containsString("Sous-total : 227,00 $")))
                .andExpect(jsonPath("$.body", containsString("Taxes (TPS + TVQ 14,975 %) : 34,00 $")))
                .andExpect(jsonPath("$.body", containsString("Total : 261,00 $")))
                .andExpect(jsonPath("$.body", containsString("Rabais de bienvenue inclus.\nalice\nEcoNet Propreté")));
        verify(mailSender, org.mockito.Mockito.never()).send(any(MimeMessage.class));

        // send a visit confirmation with the ICS invite
        String visit = json.writeValueAsString(Map.of("templateKey", "visit_confirmation", "visitAt", "2026-11-10T14:00:00"));
        mvc.perform(post("/api/leads/{id}/emails", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON).content(visit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.subject").value("Confirmation de visite – CPE Les Lucioles"))
                .andExpect(jsonPath("$.preview.body", containsString("le mardi 10 novembre 2026 à 14 h 00.")))
                .andExpect(jsonPath("$.contact.contactType").value("EMAIL"))
                .andExpect(jsonPath("$.contact.outcome").value(nullValue()))
                .andExpect(jsonPath("$.contact.username").value("alice"))
                .andExpect(jsonPath("$.contact.notes", startsWith("Confirmation de visite – CPE Les Lucioles\n\nBonjour Mme Roy,")));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Confirmation de visite – CPE Les Lucioles");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("roy@example.com");
        assertThat(message.getFrom()[0].toString()).contains("ventes@example.test");
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        String text = null;
        String ics = null;
        for (int i = 0; i < multipart.getCount(); i++) {
            var part = multipart.getBodyPart(i);
            if ("visite.ics".equals(part.getFileName())) {
                ics = new String(((InputStream) part.getContent()).readAllBytes(), StandardCharsets.UTF_8);
            } else if (part.getContent() instanceof MimeMultipart inner) {
                text = inner.getBodyPart(0).getContent().toString();
            } else {
                text = part.getContent().toString();
            }
        }
        assertThat(text).contains("Je vous confirme notre visite à CPE Les Lucioles le mardi 10 novembre 2026 à 14 h 00.");
        assertThat(ics).isNotNull()
                .contains("BEGIN:VCALENDAR").contains("METHOD:REQUEST")
                .contains("SUMMARY:Visite EcoNet Propreté – CPE Les Lucioles")
                .contains("DTSTART:20261110T190000Z").contains("DTEND:20261110T200000Z")
                .contains("LOCATION:123 Rue Saint-Denis\\, Montréal\\, QC H2X 1K1")
                .contains("ATTENDEE;CN=\"Mme Roy\"");
        mvc.perform(get("/api/contacts/business/{id}", lead.getId()).with(BOB))
                .andExpect(jsonPath("$[0].contactType").value("EMAIL"));
        mvc.perform(get("/api/businesses/{id}", lead.getId()).with(BOB)).andExpect(jsonPath("$.contactCount").value(1));

        // validation
        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"visit_confirmation\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("visitAt")));
        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"newsletter\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"missed_you\",\"language\":\"fr\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/leads/{id}/emails", noEmail.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"intro\",\"language\":\"en\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("recipient")));
        mvc.perform(post("/api/leads/{id}/emails", lead.getId()).with(VIEWER).contentType(MediaType.APPLICATION_JSON).content(visit))
                .andExpect(status().isForbidden());

        // rate limit: 2 per hour in this test context
        String intro = "{\"templateKey\":\"intro\",\"language\":\"en\",\"to\":\"someone@example.com\"}";
        mvc.perform(post("/api/leads/{id}/emails", lead.getId()).with(CAROL).contentType(MediaType.APPLICATION_JSON).content(intro))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preview.body", startsWith("Hello Mme Roy,")));
        mvc.perform(post("/api/leads/{id}/emails", lead.getId()).with(CAROL).contentType(MediaType.APPLICATION_JSON).content(intro))
                .andExpect(status().isOk());
        mvc.perform(post("/api/leads/{id}/emails", lead.getId()).with(CAROL).contentType(MediaType.APPLICATION_JSON).content(intro))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error", containsString("per hour")));
        // preview is not rate limited
        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(CAROL).contentType(MediaType.APPLICATION_JSON).content(intro))
                .andExpect(status().isOk());

        // templates admin
        mvc.perform(get("/api/email/templates").with(VIEWER)).andExpect(jsonPath("$", hasSize(3)));
        UUID templateId = templateRepository.findByKeyAndLanguageAndActiveTrue("intro", "en").orElseThrow().getId();
        mvc.perform(put("/api/email/templates/{id}", templateId).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Hi\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/email/templates/{id}", templateId).with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Hi {{businessName}}\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("Hi {{businessName}}"))
                .andExpect(jsonPath("$.body", startsWith("Hello")));
    }

    // ------------------------------------------------------------------ pricing

    @Test
    void pricingEndpointsMirrorTheWebsite() throws Exception {
        mvc.perform(get("/api/pricing").with(VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePrices.residential").value(120))
                .andExpect(jsonPath("$.addOnPrices.fridge").value(35))
                .andExpect(jsonPath("$.frequencyDiscounts.weekly").value(0.15))
                .andExpect(jsonPath("$.areaMultipliers.over_2500").value(nullValue()))
                .andExpect(jsonPath("$.propertyTypeMultipliers.house").value(1.15))
                .andExpect(jsonPath("$.taxRate").value(0.14975));
        mvc.perform(post("/api/pricing/estimate").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"residential\",\"area\":\"under_800\",\"propertyType\":\"apartment\",\"addOnIds\":[],\"frequency\":\"one-time\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(120)).andExpect(jsonPath("$.taxes").value(18)).andExpect(jsonPath("$.total").value(138));
        mvc.perform(post("/api/pricing/estimate").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"residential\",\"area\":\"1500_2500\",\"propertyType\":\"house\",\"addOnIds\":[\"oven\",\"fridge\"],\"frequency\":\"weekly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(227)).andExpect(jsonPath("$.taxes").value(34)).andExpect(jsonPath("$.total").value(261));
        mvc.perform(post("/api/pricing/estimate").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"spa\",\"area\":\"under_800\",\"propertyType\":\"house\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ dashboard: funnel, best hours, scoreboard

    @Test
    void funnelBestHoursAndScoreboardOnATinyFixture() throws Exception {
        LocalDate monday = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        Business a = lead("Lead A", "Montréal", "5145550040", 50, LeadStatus.WON, alice, null);
        Business b = lead("Lead B", "Montréal", "5145550041", 50, LeadStatus.CONTACTED, alice, null);
        call(a, alice, CallOutcome.NO_ANSWER, monday.atTime(10, 15), 20);
        call(a, alice, CallOutcome.INTERESTED, monday.atTime(10, 40), 400);
        call(a, alice, CallOutcome.WON, monday.atTime(14, 5), 600);
        call(b, alice, CallOutcome.VOICEMAIL, monday.plusDays(5).atTime(11, 0), 30); // Saturday: outside the grid
        String from = monday.toString();

        mvc.perform(get("/api/dashboard/funnel").with(VIEWER).param("from", from).param("to", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dials").value(3))
                .andExpect(jsonPath("$.connects").value(2))
                .andExpect(jsonPath("$.conversations").value(2))
                .andExpect(jsonPath("$.interested").value(1))
                .andExpect(jsonPath("$.quotes").value(0))
                .andExpect(jsonPath("$.won").value(1));
        mvc.perform(get("/api/dashboard/funnel").with(VIEWER).param("from", from).param("to", monday.plusDays(6).toString()))
                .andExpect(jsonPath("$.dials").value(4)).andExpect(jsonPath("$.connects").value(2));

        mvc.perform(get("/api/dashboard/best-hours").with(VIEWER).param("from", from).param("to", monday.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(55)))
                .andExpect(jsonPath("$[0].dow").value(1)).andExpect(jsonPath("$[0].hour").value(8)).andExpect(jsonPath("$[0].calls").value(0))
                .andExpect(jsonPath("$[2].hour").value(10)).andExpect(jsonPath("$[2].calls").value(2))
                .andExpect(jsonPath("$[2].connects").value(1)).andExpect(jsonPath("$[2].positive").value(1))
                .andExpect(jsonPath("$[6].hour").value(14)).andExpect(jsonPath("$[6].calls").value(1)).andExpect(jsonPath("$[6].positive").value(1))
                .andExpect(jsonPath("$[54].dow").value(5)).andExpect(jsonPath("$[54].hour").value(18))
                .andExpect(jsonPath("$[?(@.dow > 5)]", hasSize(0)));

        // today: alice 3 calls (2 conversations, 1 quote), bob 5 calls; streak of 3 business days for alice
        LocalDateTime todayStart = today.atStartOfDay();
        call(b, alice, CallOutcome.NO_ANSWER, todayStart.plusMinutes(1), 10);
        call(b, alice, CallOutcome.INTERESTED, todayStart.plusMinutes(2), 200);
        call(b, alice, CallOutcome.QUOTE_SENT, todayStart.plusMinutes(3), 300);
        for (int i = 0; i < 5; i++) {
            call(a, bob, CallOutcome.NO_ANSWER, todayStart.plusMinutes(10 + i), 10);
        }
        LocalDate d0 = previousBusinessDayOrSame(today);
        if (!d0.equals(today)) {
            call(b, alice, CallOutcome.NO_ANSWER, d0.atTime(9, 0), 10);
        }
        LocalDate d1 = previousBusinessDayOrSame(d0.minusDays(1));
        LocalDate d2 = previousBusinessDayOrSame(d1.minusDays(1));
        call(b, alice, CallOutcome.NO_ANSWER, d1.atTime(9, 0), 10);
        call(b, alice, CallOutcome.NO_ANSWER, d2.atTime(9, 0), 10);
        // the Monday of the funnel fixture may extend the streak (e.g. on a Thursday: Mon-Thu = 4)
        int expectedStreak = DashboardService.streakDays(java.util.Set.of(d0, d1, d2, monday), today);
        assertThat(expectedStreak).isGreaterThanOrEqualTo(3);

        mvc.perform(get("/api/dashboard/me/today").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callsToday").value(3))
                .andExpect(jsonPath("$.conversationsToday").value(2))
                .andExpect(jsonPath("$.quotesToday").value(1))
                .andExpect(jsonPath("$.wonToday").value(0))
                .andExpect(jsonPath("$.callTarget").value(40))
                .andExpect(jsonPath("$.conversationTarget").value(6))
                .andExpect(jsonPath("$.streakDays").value(expectedStreak))
                .andExpect(jsonPath("$.bestDayCalls").value(3))
                .andExpect(jsonPath("$.rankToday.position").value(2))
                .andExpect(jsonPath("$.rankToday.of", greaterThanOrEqualTo(3)));
        mvc.perform(get("/api/dashboard/me/today").with(BOB))
                .andExpect(jsonPath("$.callsToday").value(5)).andExpect(jsonPath("$.rankToday.position").value(1))
                .andExpect(jsonPath("$.streakDays").value(d0.equals(today) ? 1 : 0));

        // targets
        mvc.perform(get("/api/me/targets").with(ALICE)).andExpect(jsonPath("$.callTarget").value(40)).andExpect(jsonPath("$.conversationTarget").value(6));
        mvc.perform(put("/api/me/targets").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"callTarget\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.callTarget").value(50)).andExpect(jsonPath("$.conversationTarget").value(6));
        mvc.perform(put("/api/me/targets").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"dailyConversationTarget\":9}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conversationTarget").value(9));
        mvc.perform(put("/api/me/targets").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"callTarget\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/targets").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard/me/today").with(ALICE)).andExpect(jsonPath("$.callTarget").value(50)).andExpect(jsonPath("$.conversationTarget").value(9));
        mvc.perform(put("/api/users/{id}/targets", bob.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"callTarget\":30}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/{id}/targets", bob.getId()).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"callTarget\":30}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.callTarget").value(30));
        mvc.perform(get("/api/me/targets").with(BOB)).andExpect(jsonPath("$.callTarget").value(30));
        mvc.perform(get("/api/users/{id}/targets", bob.getId()).with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users/{id}/targets", bob.getId()).with(ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.callTarget").value(30)).andExpect(jsonPath("$.conversationTarget").exists());
        mvc.perform(get("/api/users").with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + bob.getId() + "')].username").value(bob.getUsername()))
                .andExpect(jsonPath("$[?(@.id=='" + bob.getId() + "')].callTarget").value(30))
                .andExpect(jsonPath("$[?(@.id=='" + bob.getId() + "')].active").value(true))
                .andExpect(jsonPath("$[0].conversationTarget").exists());
        mvc.perform(get("/api/users/{id}/targets", java.util.UUID.randomUUID()).with(ADMIN)).andExpect(status().isNotFound());

        // team
        mvc.perform(post("/api/leads/{id}/claim", b.getId()).with(BOB)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/team/today").with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("bob"))
                .andExpect(jsonPath("$[0].callsToday").value(5))
                .andExpect(jsonPath("$[0].status").value("calling"))
                .andExpect(jsonPath("$[0].currentLeadName").value("Lead B"))
                .andExpect(jsonPath("$[1].username").value("alice"))
                .andExpect(jsonPath("$[1].callsToday").value(3))
                .andExpect(jsonPath("$[1].conversationsToday").value(2))
                .andExpect(jsonPath("$[1].status", isOneOf("idle", "offline")))
                .andExpect(jsonPath("$[?(@.username == 'viewer')]", hasSize(0)));
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void notificationsListFollowUpsAndClosingTenders() throws Exception {
        LocalDateTime todayStart = today.atStartOfDay();
        lead("Overdue Mine", "Montréal", "5145550050", 50, LeadStatus.CONTACTED, alice, today.minusDays(1).atTime(10, 0));
        lead("Due Unassigned", "Montréal", "5145550051", 50, LeadStatus.NEW, null, todayStart.plusMinutes(1));
        lead("Bob's", "Montréal", "5145550052", 50, LeadStatus.CONTACTED, bob, today.minusDays(1).atTime(10, 0));
        lead("Won", "Montréal", "5145550053", 50, LeadStatus.WON, alice, today.minusDays(1).atTime(10, 0));
        lead("Tomorrow", "Montréal", "5145550054", 50, LeadStatus.CONTACTED, alice, today.plusDays(1).atTime(10, 0));
        tender("Entretien ménager – école", TenderStatus.REVIEWING, now.plusHours(24));
        tender("Nettoyage – hôpital", TenderStatus.NEW, now.plusHours(24));
        tender("Conciergerie – aréna", TenderStatus.BIDDING, now.plusHours(72));
        tender("Déjà fermé", TenderStatus.BIDDING, now.minusHours(1));

        mvc.perform(get("/api/notifications").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].kind").value("follow_up_overdue"))
                .andExpect(jsonPath("$[0].title").value("Overdue Mine"))
                .andExpect(jsonPath("$[0].leadId").exists())
                .andExpect(jsonPath("$[0].id", startsWith("follow_up_overdue:")))
                .andExpect(jsonPath("$[1].kind").value("follow_up_due"))
                .andExpect(jsonPath("$[1].title").value("Due Unassigned"))
                .andExpect(jsonPath("$[2].kind").value("tender_closing"))
                .andExpect(jsonPath("$[2].title").value("Entretien ménager – école"))
                .andExpect(jsonPath("$[2].tenderId").exists())
                .andExpect(jsonPath("$[2].body", containsString("Fermeture dans")))
                .andExpect(jsonPath("$[2].at").exists());
        mvc.perform(get("/api/notifications").with(BOB))
                .andExpect(jsonPath("$[?(@.kind == 'follow_up_overdue')].title", contains("Bob's")))
                .andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/notifications").with(VIEWER)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ roles

    @Test
    void viewerIsForbiddenOnEveryWrite() throws Exception {
        Business lead = lead("Lecture seule", "Montréal", "5145550060", 50, LeadStatus.NEW, null, null);
        String j = "{\"x\":1}";
        for (MockHttpServletRequestBuilder write : List.of(
                post("/api/leads/{id}/claim", lead.getId()),
                delete("/api/leads/{id}/claim", lead.getId()),
                post("/api/leads/{id}/calls", lead.getId()).content("{\"outcome\":\"NO_ANSWER\"}"),
                post("/api/leads/{id}/snooze", lead.getId()).content("{\"until\":\"2030-01-01T10:00:00\"}"),
                post("/api/leads/{id}/emails", lead.getId()).content("{\"templateKey\":\"intro\"}"),
                post("/api/leads/{id}/emails/preview", lead.getId()).content("{\"templateKey\":\"intro\"}"),
                patch("/api/businesses/{id}/qualification", lead.getId()).content(j),
                post("/api/scripts").content(j),
                put("/api/scripts/{id}", UUID.randomUUID()).content(j),
                put("/api/email/templates/{id}", UUID.randomUUID()).content(j),
                put("/api/me/targets").content("{\"callTarget\":10}"),
                put("/api/users/{id}/targets", alice.getId()).content("{\"callTarget\":10}"))) {
            mvc.perform(write.with(VIEWER).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").exists());
        }
        for (String read : List.of("/api/leads/queue", "/api/scripts/all", "/api/email/templates", "/api/email/status",
                "/api/pricing", "/api/notifications", "/api/dashboard/me/today", "/api/dashboard/team/today",
                "/api/dashboard/funnel", "/api/dashboard/best-hours", "/api/dashboard/conversion-by-type", "/api/me/targets",
                "/api/businesses/" + lead.getId() + "/qualification")) {
            mvc.perform(get(read).with(VIEWER)).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ fixtures

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

    private Business lead(String name, String city, String phone, int quality, LeadStatus status, User assignee,
                          LocalDateTime followUp) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType(name.startsWith("Resto") ? "Restaurant" : "CPE");
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

    private Contact call(Business lead, User user, CallOutcome outcome, LocalDateTime at, int durationSeconds) {
        Contact c = new Contact();
        c.setBusiness(lead);
        c.setUser(user);
        c.setContactType(Contact.ContactType.APPEL);
        c.setContactDate(at);
        c.setContactStatus(outcome.name());
        c.setOutcome(outcome.toContactOutcome());
        c.setDurationSeconds(durationSeconds);
        return contactRepository.save(c);
    }

    private CallScript script(String name, String businessType, String language) {
        CallScript s = new CallScript();
        s.setName(name);
        s.setBusinessType(businessType);
        s.setLanguage(language);
        s.setActive(true);
        s.setSections(List.of(
                new ScriptSection("opening", "Ouverture", "Bonjour, ici {{callerName}} pour {{businessName}}"),
                new ScriptSection("questions", "Questions", "1. Qui s'occupe de l'entretien?"),
                new ScriptSection("objections", "Objections", "« On a déjà quelqu'un. »\n→ Parfait..."),
                new ScriptSection("closing", "Conclusion", "Visite gratuite mardi?")));
        return scriptRepository.save(s);
    }

    private EmailTemplate template(String key, String language, String subject, String body) {
        EmailTemplate t = new EmailTemplate();
        t.setKey(key);
        t.setLanguage(language);
        t.setSubject(subject);
        t.setBody(body);
        t.setActive(true);
        return templateRepository.save(t);
    }

    private Tender tender(String title, TenderStatus status, LocalDateTime closingAt) {
        Tender t = new Tender();
        t.setSource(TenderSource.SEAO);
        t.setExternalId(UUID.randomUUID().toString());
        t.setTitle(title);
        t.setUrl("https://example.com/" + UUID.randomUUID());
        t.setStatus(status);
        t.setClosingAt(closingAt);
        t.setBuyer("Ville de Montréal");
        return tenderRepository.save(t);
    }

    private static LocalDate previousBusinessDayOrSame(LocalDate date) {
        LocalDate d = date;
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
