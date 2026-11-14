package com.econet.leads.controller;

import com.econet.leads.model.Business;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.CallScriptRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.report.WeeklyReportScheduler;
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
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P4: objections on calls, voicemail script section, weekly coaching email (SMTP configured). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.mail.host=smtp.test.invalid",
        "app.mail.username=ventes@example.test",
        "app.crm-url=https://crm.example.test/"
})
class FasterCallsApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired CallScriptRepository scriptRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired WeeklyReportScheduler scheduler;
    @MockitoBean JavaMailSender mailSender;

    static final RequestPostProcessor ALICE = user("p4alice").roles("USER");
    static final RequestPostProcessor BOB = user("p4bob").roles("USER");
    static final RequestPostProcessor VIEWER = user("p4viewer").roles("VIEWER");
    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    User alice;
    User bob;
    LocalDate lastMonday;

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        alice = ensureUser("p4alice", User.UserRole.USER);
        bob = ensureUser("p4bob", User.UserRole.USER);
        ensureUser("p4viewer", User.UserRole.VIEWER);
        for (User u : List.of(alice, bob)) {
            u.setWeeklyReport(true);
            userRepository.save(u);
        }
        lastMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        org.mockito.Mockito.reset(mailSender);
        given(mailSender.createMimeMessage()).willAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    // ------------------------------------------------------------------ objections

    @Test
    void callsStoreCleanedObjectionsAndContactsExposeThem() throws Exception {
        Business lead = lead("CPE Les Lucioles");
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"outcome":"NOT_INTERESTED","durationSeconds":42,
                                 "objections":["  Déjà un fournisseur ", "pas de budget", "PAS DE BUDGET", "  "]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.objections", contains("Déjà un fournisseur", "pas de budget")));

        mvc.perform(get("/api/contacts/business/{id}", lead.getId()).with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].objections", contains("Déjà un fournisseur", "pas de budget")));
    }

    @Test
    void callsWithoutObjectionsHaveNullObjections() throws Exception {
        Business lead = lead("CPE Sans objection");
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NO_ANSWER\",\"objections\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.objections").doesNotExist());
    }

    @Test
    void objectionsAreValidated() throws Exception {
        Business lead = lead("CPE Validation");
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_INTERESTED\",\"objections\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_INTERESTED\",\"objections\":[\"" + "x".repeat(81) + "\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(contactRepository.count()).isZero();
    }

    @Test
    void viewersCannotLogCalls() throws Exception {
        Business lead = lead("CPE Viewer");
        mvc.perform(post("/api/leads/{id}/calls", lead.getId()).with(VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NO_ANSWER\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ voicemail section

    @Test
    void scriptsAcceptTheVoicemailSectionAndStillRejectUnknownKeys() throws Exception {
        String ok = """
                {"name":"Script P4","language":"fr","sections":[
                  {"key":"opening","title":"Ouverture","body":"Bonjour"},
                  {"key":"voicemail","title":"Message vocal","body":"Bonjour, ici {{callerName}}."}]}""";
        mvc.perform(post("/api/scripts").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].key").value("voicemail"));
        mvc.perform(post("/api/scripts").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(ok.replace("\"voicemail\"", "\"messagerie\"")))
                .andExpect(status().isBadRequest());
        scriptRepository.deleteAll();
    }

    // ------------------------------------------------------------------ preferences

    @Test
    void preferencesDefaultToOnAndCanBeToggled() throws Exception {
        mvc.perform(get("/api/me/preferences").with(ALICE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.weeklyReport").value(true));
        mvc.perform(put("/api/me/preferences").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"weeklyReport\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.weeklyReport").value(false));
        assertThat(userRepository.findByUsername("p4alice").orElseThrow().getWeeklyReport()).isFalse();
        mvc.perform(get("/api/me/preferences").with(ALICE)).andExpect(jsonPath("$.weeklyReport").value(false));
        mvc.perform(put("/api/me/preferences").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/preferences").with(VIEWER).contentType(MediaType.APPLICATION_JSON).content("{\"weeklyReport\":true}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ weekly report

    @Test
    void previewComparesLastWeekWithTheWeekBeforeAndListsTopObjections() throws Exception {
        Business a = lead("Client A");
        Business b = lead("Client B");
        Business c = lead("Client C");
        LocalDateTime tue = lastMonday.plusDays(1).atTime(10, 15);
        // last week: 5 calls, 3 conversations, 4 connects, 1 quote, 1 win
        call(a, alice, CallOutcome.NO_ANSWER, tue, null);
        call(a, alice, CallOutcome.VOICEMAIL, tue.plusMinutes(5), null);
        call(b, alice, CallOutcome.NOT_INTERESTED, tue.plusMinutes(10), List.of("Pas de budget", "Déjà un fournisseur"));
        call(c, alice, CallOutcome.QUOTE_SENT, tue.plusHours(1), List.of("pas de budget"));
        call(c, alice, CallOutcome.WON, tue.plusHours(2), null);
        // someone else's call must not count
        call(a, bob, CallOutcome.INTERESTED, tue, List.of("Trop cher"));
        // the week before: 2 calls
        call(a, alice, CallOutcome.NO_ANSWER, lastMonday.minusDays(6).atTime(9, 0), null);
        call(a, alice, CallOutcome.INTERESTED, lastMonday.minusDays(6).atTime(9, 30), null);

        var result = mvc.perform(post("/api/reports/weekly/preview").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Votre semaine chez EcoNet Propreté : 5 appels, 3 conversations"))
                .andReturn();
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        String text = (String) body.get("text");
        assertThat(text).contains("Bonjour p4alice")
                .contains("- Appels : 5 (+3 vs semaine précédente)")
                .contains("- Conversations : 3 (+2 vs semaine précédente)")
                .contains("- Taux de jointure : 60 %")
                .contains("- Soumissions envoyées : 1")
                .contains("- Contrats gagnés : 1")
                .contains("- Pas de budget (2 fois)")
                .contains("- Déjà un fournisseur (1 fois)")
                .contains("https://crm.example.test/call")
                .doesNotContain("Trop cher");
        assertThat((String) body.get("html")).contains("<table").contains("Ouvrir le CRM").contains("https://crm.example.test/call");
    }

    @Test
    void previewEscapesHtmlAndWorksWithoutActivity() throws Exception {
        Business a = lead("Client XSS");
        call(a, bob, CallOutcome.NOT_INTERESTED, lastMonday.plusDays(2).atTime(11, 0), List.of("<script>alert(1)</script>"));
        var result = mvc.perform(post("/api/reports/weekly/preview").with(BOB)).andExpect(status().isOk()).andReturn();
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat((String) body.get("html")).contains("&lt;script&gt;").doesNotContain("<script>");

        mvc.perform(post("/api/reports/weekly/preview").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Votre semaine chez EcoNet Propreté : 0 appels, 0 conversations"))
                .andExpect(jsonPath("$.text", not(containsString("Objections"))));
    }

    @Test
    void previewIsForCallersNotViewers() throws Exception {
        mvc.perform(post("/api/reports/weekly/preview").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/reports/weekly/preview")).andExpect(status().isUnauthorized());
    }

    @Test
    void sendNowIsAdminOnlyAndEmailsOptedInUsersWithHtmlAndText() throws Exception {
        mvc.perform(post("/api/reports/weekly/send-now").with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/reports/weekly/send-now").with(VIEWER)).andExpect(status().isForbidden());

        bob.setWeeklyReport(false);
        userRepository.save(bob);
        mvc.perform(post("/api/reports/weekly/send-now").with(ADMIN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"));

        List<MimeMessage> messages = new ArrayList<>();
        List<String> recipients = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
            verify(mailSender, atLeast(0)).send(sent.capture());
            messages = sent.getAllValues();
            recipients.clear();
            for (MimeMessage m : messages) {
                recipients.add(m.getAllRecipients()[0].toString());
            }
            if (recipients.contains("p4alice@example.com")) break;
            Thread.sleep(100);
        }
        assertThat(recipients).contains("p4alice@example.com").doesNotContain("p4bob@example.com", "p4viewer@example.com");
        MimeMessage mine = messages.get(recipients.indexOf("p4alice@example.com"));
        mine.saveChanges();
        assertThat(mine.getSubject()).startsWith("Votre semaine chez EcoNet Propreté");
        assertThat(mine.getContent()).isInstanceOf(MimeMultipart.class);
    }

    @Test
    void schedulerSendsWhenSmtpIsConfigured() {
        scheduler.run();
        verify(mailSender, atLeastOnce()).send(any(MimeMessage.class));
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

    private Business lead(String name) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("CPE");
        b.setPhone("5145550123");
        b.setPhoneNormalized("5145550123");
        b.setDataSource("TEST");
        b.setDataQualityScore(80);
        b.setLeadStatus(LeadStatus.NEW);
        return businessRepository.save(b);
    }

    private Contact call(Business lead, User by, CallOutcome outcome, LocalDateTime at, List<String> objections) {
        Contact c = new Contact();
        c.setBusiness(lead);
        c.setUser(by);
        c.setContactType(Contact.ContactType.APPEL);
        c.setContactDate(at);
        c.setContactStatus(outcome.name());
        c.setOutcome(outcome.toContactOutcome());
        c.setObjections(objections);
        return contactRepository.save(c);
    }
}
