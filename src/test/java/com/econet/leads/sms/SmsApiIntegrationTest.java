package com.econet.leads.sms;

import com.econet.leads.integration.website.InboundLeadEvent;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Twilio configured (fake HTTP server), alert phone + email set, SMTP mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = {
        "app.sms.account-sid=ACtest",
        "app.sms.auth-token=secret-token",
        "app.sms.from=+14385550000",
        "app.sms.follow-up-limit-per-hour=3",
        "app.alerts.phone=514-555-0001, +15145550002",
        "app.alerts.email=owner@example.test",
        "app.crm-url=https://crm.example.test",
        "app.mail.host=smtp.test.invalid",
        "app.mail.username=ventes@example.test"
})
class SmsApiIntegrationTest {

    static final FakeTwilio TWILIO = new FakeTwilio();
    static final RequestPostProcessor ADMIN = user("sms-admin").roles("ADMIN");
    static final RequestPostProcessor ALICE = user("sms-alice").roles("USER");
    static final RequestPostProcessor BOB = user("sms-bob").roles("USER");
    static final RequestPostProcessor VIEWER = user("sms-viewer").roles("VIEWER");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.sms.api-base", TWILIO::baseUrl);
    }

    @AfterAll
    static void stop() {
        TWILIO.server.stop(0);
    }

    @MockitoBean JavaMailSender mailSender;
    @Autowired MockMvc mvc;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SmsMessageRepository smsMessages;
    @Autowired AlertService alertService;
    @Autowired ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        TWILIO.reset();
        for (String name : new String[]{"sms-admin", "sms-alice", "sms-bob", "sms-viewer"}) {
            if (userRepository.findByUsername(name).isEmpty()) {
                User u = new User();
                u.setUsername(name);
                u.setEmail(name + "@example.com");
                u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
                u.setRole(name.endsWith("admin") ? User.UserRole.ADMIN : name.endsWith("viewer") ? User.UserRole.VIEWER : User.UserRole.USER);
                u.setActive(true);
                userRepository.save(u);
            }
        }
        given(mailSender.createMimeMessage()).willAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    Business lead(String name, String phone) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("CPE");
        b.setDataSource("TEST");
        b.setLeadStatus(LeadStatus.NEW);
        b.setPhone(phone);
        return businessRepository.save(b);
    }

    String sms(String templateKey, boolean consent) {
        return "{\"templateKey\":\"" + templateKey + "\",\"language\":\"fr\",\"consent\":" + consent + "}";
    }

    // ------------------------------------------------------------------ integrations status

    @Test
    void statusReportsProvidersForUsersOnly() throws Exception {
        mvc.perform(get("/api/integrations/status").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sms").value(true))
                .andExpect(jsonPath("$.alertsPhone").value(true))
                .andExpect(jsonPath("$.alertsEmail").value(true))
                .andExpect(jsonPath("$.smtp").value(true))
                .andExpect(jsonPath("$.places").value(false))
                .andExpect(jsonPath("$.placesUsedToday").value(0))
                .andExpect(jsonPath("$.placesDailyLimit").value(100))
                .andExpect(jsonPath("$.googleReviewUrl").value(false))
                .andExpect(jsonPath("$.websiteDb").value(false))
                .andExpect(jsonPath("$.crmUrl").value("https://crm.example.test"))
                .andExpect(jsonPath("$.publicUrl").isNotEmpty());
        mvc.perform(get("/api/integrations/status").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/integrations/status")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ follow-up SMS

    @Test
    void followUpSendsTextWritesContactAndRecordsConsent() throws Exception {
        Business b = lead("Clinique Soleil", "(514) 555-1234");

        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("missed_call", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("sent"));

        FakeTwilio.Call call = TWILIO.calls.get(0);
        assertThat(call.form().get("To")).isEqualTo("+15145551234");
        assertThat(call.form().get("Body")).contains("Clinique Soleil").contains("sms-alice")
                .endsWith("Répondez STOP pour vous désabonner.");

        Contact note = contactRepository.findAll().stream()
                .filter(c -> c.getBusiness().getId().equals(b.getId())).findFirst().orElseThrow();
        assertThat(note.getContactType()).isEqualTo(Contact.ContactType.NOTE);
        assertThat(note.getNotes()).startsWith("Texto envoyé : ").contains("Consentement verbal obtenu par sms-alice");

        SmsMessage row = smsMessages.findAll().stream().filter(m -> b.getId().equals(m.getBusinessId())).findFirst().orElseThrow();
        assertThat(row.getKind()).isEqualTo("follow_up");
        assertThat(row.getStatus()).isEqualTo("sent");
        assertThat(row.getToMasked()).isEqualTo("***1234");
        assertThat(row.getProviderSid()).startsWith("SM");
        assertThat(row.getBody()).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void followUpRequiresConsentValidTemplateAndRole() throws Exception {
        Business b = lead("Sans consentement", "514-555-9999");
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("missed_call", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"missed_call\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("nope", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("missed_call", true)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/leads/{id}/sms", UUID.randomUUID()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("missed_call", true)))
                .andExpect(status().isNotFound());
        assertThat(TWILIO.calls).isEmpty();
    }

    @Test
    void followUpWithoutUsablePhoneIs400() throws Exception {
        Business b = lead("Sans téléphone", null);
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("quote_followup", true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void providerFailureIs502AndIsLoggedWithoutAContact() throws Exception {
        Business b = lead("Échec fournisseur", "514-555-7777");
        TWILIO.nextStatus = 400;
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("after_voicemail", true)))
                .andExpect(status().isBadGateway());
        assertThat(smsMessages.findAll()).anyMatch(m -> b.getId().equals(m.getBusinessId()) && "failed".equals(m.getStatus()));
        assertThat(contactRepository.findAll()).noneMatch(c -> c.getBusiness().getId().equals(b.getId()));
    }

    @Test
    void followUpsAreLimitedPerUserPerHour() throws Exception {
        Business b = lead("Limite", "514-555-3333");
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(BOB).contentType(MediaType.APPLICATION_JSON)
                            .content(sms("missed_call", true)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(BOB).contentType(MediaType.APPLICATION_JSON)
                        .content(sms("missed_call", true)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void previewRendersTheTemplateForTheLead() throws Exception {
        Business b = lead("Aperçu inc.", "514-555-2222");
        mvc.perform(get("/api/leads/{id}/sms/preview", b.getId()).param("templateKey", "quote_followup").param("language", "en").with(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Aperçu inc."), org.hamcrest.Matchers.endsWith("Reply STOP to opt out."))));
    }

    // ------------------------------------------------------------------ owner alerts

    @Test
    void inboundLeadTextsEveryAlertPhoneOnceAndEmailsTheOwner() throws Exception {
        UUID leadId = UUID.randomUUID();
        InboundLeadEvent event = new InboundLeadEvent(leadId, "Marie Tremblay", "booking", "Demande web – résidentiel",
                new BigDecimal("261.00"), "(514) 555-8888");

        alertService.handle(event);
        alertService.handle(event); // same lead again: throttled

        assertThat(TWILIO.calls).hasSize(2);
        assertThat(TWILIO.calls).extracting(c -> c.form().get("To")).containsExactlyInAnyOrder("+15145550001", "+15145550002");
        String body = TWILIO.calls.get(0).form().get("Body");
        assertThat(body).startsWith("EcoNet: nouvelle demande du site — Marie Tremblay, résidentiel, 261 $")
                .contains("Appeler: (514) 555-8888").endsWith("https://crm.example.test/call?leadId=" + leadId);
        assertThat(smsMessages.findAll()).filteredOn(m -> leadId.equals(m.getBusinessId()))
                .hasSize(2).allMatch(m -> "alert".equals(m.getKind()) && m.getBody() == null);
        verify(mailSender, atLeastOnce()).send(any(MimeMessage.class));
    }

    @Test
    void aProviderOutageNeverThrowsFromTheAlert() {
        TWILIO.nextStatus = 500;
        InboundLeadEvent event = new InboundLeadEvent(UUID.randomUUID(), "Panne", "contact", "Demande web – contact", null, null);
        alertService.handle(event);
        assertThat(smsMessages.findAll()).filteredOn(m -> event.leadId().equals(m.getBusinessId()))
                .isNotEmpty().allMatch(m -> "failed".equals(m.getStatus()));
    }

    @Test
    void publishedInboundEventTriggersTheAlertInTheBackground() throws Exception {
        InboundLeadEvent event = new InboundLeadEvent(UUID.randomUUID(), "Via événement", "contact", "Demande web – contact", null, "514-555-4444");
        publisher.publishEvent(event);
        long deadline = System.currentTimeMillis() + 5000;
        while (TWILIO.calls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(TWILIO.calls).isNotEmpty();
        assertThat(TWILIO.calls.get(0).form().get("Body")).contains("Via événement");
    }

    // ------------------------------------------------------------------ test alert

    @Test
    void testAlertIsAdminOnlyAndReportsWhatWasSent() throws Exception {
        mvc.perform(post("/api/integrations/test-alert").with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/integrations/test-alert").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smsTargets").value(2))
                .andExpect(jsonPath("$.smsSent").value(2))
                .andExpect(jsonPath("$.emailTargets").value(1))
                .andExpect(jsonPath("$.emailSent").value(1));
        assertThat(TWILIO.calls).hasSize(2);
        assertThat(TWILIO.calls.get(0).form().get("Body")).contains("alerte test");
    }
}
