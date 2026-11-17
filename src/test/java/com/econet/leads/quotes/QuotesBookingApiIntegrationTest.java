package com.econet.leads.quotes;

import com.econet.leads.model.Availability;
import com.econet.leads.model.BookingLink;
import com.econet.leads.model.Client;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.EmailTemplate;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.Quote;
import com.econet.leads.model.User;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.AvailabilityRepository;
import com.econet.leads.repository.BookingLinkRepository;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ClientRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.EmailTemplateRepository;
import com.econet.leads.repository.QuoteRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.repository.VisitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quotes (PDF, email, public accept / decline) and visit booking (links, slots, public booking) on H2, with a mock SMTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.mail.host=smtp.test.invalid",
        "app.mail.username=ventes@example.test",
        "app.public-url=https://api.example.test",
        "app.public.rate-limit-per-minute=100000"
})
class QuotesBookingApiIntegrationTest {

    static final RequestPostProcessor QUINN = user("quinn").roles("USER");
    static final RequestPostProcessor VIEWER = user("vera").roles("VIEWER");
    static final RequestPostProcessor ADMIN = user("quotes-admin").roles("ADMIN");
    static final String SAME_ORIGIN = "http://localhost";
    static final String ESTIMATE = "{\"estimate\":{\"service\":\"residential\",\"area\":\"under_800\",\"propertyType\":\"apartment\","
            + "\"addOnIds\":[\"windows\"],\"frequency\":\"weekly\"},\"language\":\"fr\"}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired QuoteRepository quoteRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired BookingLinkRepository linkRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired EmailTemplateRepository templateRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SseEventService sse;
    @MockitoBean JavaMailSender mailSender;

    User quinn;
    Business lead;

    @BeforeEach
    void setUp() {
        cleanup();
        quinn = ensureUser("quinn", User.UserRole.USER);
        ensureUser("vera", User.UserRole.VIEWER);
        ensureUser("quotes-admin", User.UserRole.ADMIN);
        availabilityRepository.deleteAll();
        for (int day = 1; day <= 5; day++) {
            Availability a = new Availability();
            a.setWeekday(day);
            a.setStartTime(LocalTime.of(9, 0));
            a.setEndTime(LocalTime.of(17, 0));
            a.setSlotMinutes(60);
            a.setActive(true);
            availabilityRepository.save(a);
        }
        lead = newLead("Clinique <b>Soleil</b>", "client@example.com", LeadStatus.INTERESTED);
        reset(mailSender);
        given(mailSender.createMimeMessage()).willAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @AfterEach
    void cleanup() {
        linkRepository.deleteAll();
        visitRepository.deleteAll();
        quoteRepository.deleteAll();
        clientRepository.deleteAll();
        contactRepository.deleteAll();
        businessRepository.deleteAll();
    }

    // ------------------------------------------------------------------ quotes (CRM)

    @Test
    void createsAPricedQuoteWithSequentialNumbersAndConsistentLines() throws Exception {
        MvcResult res = mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTIMATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(matchesPattern("Q-\\d{4}-0001")))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.language").value("fr"))
                // residential 120 + windows 30, weekly -15 %: subtotal 128, taxes 19, total 147 (same numbers as the website)
                .andExpect(jsonPath("$.subtotal").value(128.0))
                .andExpect(jsonPath("$.tax").value(19.0))
                .andExpect(jsonPath("$.total").value(147.0))
                .andExpect(jsonPath("$.publicUrl").value(matchesPattern("https://api\\.example\\.test/public/quotes/[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.createdBy").value("quinn"))
                .andReturn();
        JsonNode quote = json.readTree(res.getResponse().getContentAsString());
        double sum = 0;
        for (JsonNode line : quote.get("lines")) sum += line.get("amount").asDouble();
        assertThat(sum).isEqualTo(quote.get("subtotal").asDouble());
        assertThat(quote.get("validUntil").asText()).isEqualTo(java.time.LocalDate.now().plusDays(30).toString());

        mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTIMATE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(matchesPattern("Q-\\d{4}-0002")));
        mvc.perform(get("/api/leads/{id}/quotes", lead.getId()).with(QUINN))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].number").value(matchesPattern("Q-\\d{4}-0002")));
    }

    @Test
    void quoteEndpointsEnforceRolesAndValidateInput() throws Exception {
        mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(VIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTIMATE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/leads/{id}/quotes", lead.getId()).with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/leads/{id}/quotes", lead.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(ESTIMATE)).andExpect(status().isUnauthorized());
        // over 2,500 sq ft cannot be priced online
        mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimate\":{\"service\":\"residential\",\"area\":\"over_2500\",\"propertyType\":\"house\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimate\":{\"service\":\"nope\",\"area\":\"under_800\",\"propertyType\":\"house\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"fr\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN).contentType(MediaType.APPLICATION_JSON)
                        .content(ESTIMATE.replace("\"fr\"", "\"de\""))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/leads/{id}/quotes", UUID.randomUUID()).with(QUINN)
                .contentType(MediaType.APPLICATION_JSON).content(ESTIMATE)).andExpect(status().isNotFound());
    }

    @Test
    void servesThePdf() throws Exception {
        UUID id = createQuote("fr");
        MvcResult res = mvc.perform(get("/api/quotes/{id}/pdf", id).with(QUINN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("Q-")))
                .andReturn();
        byte[] bytes = res.getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(1000);
        mvc.perform(get("/api/quotes/{id}/pdf", id).with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/quotes/{id}/pdf", UUID.randomUUID()).with(QUINN)).andExpect(status().isNotFound());
    }

    @Test
    void sendsTheQuoteByEmailAndMovesTheLeadToQuoteSent() throws Exception {
        UUID id = createQuote("fr");
        mvc.perform(post("/api/quotes/{id}/send", id).with(VIEWER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quotes/{id}/send", id).with(QUINN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sent"))
                .andExpect(jsonPath("$.sentAt").exists());

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("client@example.com");
        assertThat(message.getSubject()).contains("Q-");
        String text = textOf(message);
        Quote stored = quoteRepository.findById(id).orElseThrow();
        assertThat(text).contains("https://api.example.test/public/quotes/" + stored.getToken());
        assertThat(attachmentNames(message)).anyMatch(n -> n.endsWith(".pdf"));

        assertThat(businessRepository.findById(lead.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.QUOTE_SENT);
        List<Contact> contacts = contactRepository.findByBusinessIdOrderByContactDateDesc(lead.getId());
        assertThat(contacts).anyMatch(c -> c.getContactType() == Contact.ContactType.EMAIL && c.getNotes().contains("Q-"));

        // an explicit bad recipient is a 400, a missing one too
        UUID second = createQuote("fr");
        mvc.perform(post("/api/quotes/{id}/send", second).with(QUINN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"nope\"}")).andExpect(status().isBadRequest());
        lead.setEmail(null);
        businessRepository.save(lead);
        mvc.perform(post("/api/quotes/{id}/send", second).with(QUINN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/quotes/{id}/send", second).with(QUINN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"to\":\"other@example.com\"}")).andExpect(status().isOk());
    }

    @Test
    void doesNotDowngradeALeadAlreadyBeyondQuoteSent() throws Exception {
        lead.setLeadStatus(LeadStatus.WON);
        businessRepository.save(lead);
        UUID id = createQuote("en");
        mvc.perform(post("/api/quotes/{id}/send", id).with(QUINN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(businessRepository.findById(lead.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.WON);
    }

    // ------------------------------------------------------------------ public quote page

    @Test
    void publicPageShowsTheQuoteEscapedAndHardened() throws Exception {
        UUID id = createQuote("fr");
        String token = tokenOf(id);
        MvcResult res = mvc.perform(get("/public/quotes/{t}", token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(content().string(containsString("Télécharger le PDF")))
                .andExpect(content().string(containsString("J'accepte cette soumission et ses conditions")))
                .andExpect(content().string(containsString("#3f6b5a")))
                .andReturn();
        String html = res.getResponse().getContentAsString();
        assertThat(html).contains("&lt;b&gt;Soleil&lt;/b&gt;").doesNotContain("<b>Soleil</b>");
        assertThat(html).doesNotContain("<script");
        mvc.perform(get("/public/quotes/{t}/pdf", token)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));

        // unknown / malformed tokens say nothing about other quotes
        mvc.perform(get("/public/quotes/{t}", "A".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(get("/public/quotes/{t}", "short")).andExpect(status().isNotFound());
        mvc.perform(get("/public/quotes/{t}/pdf", "A".repeat(43))).andExpect(status().isNotFound());
        // English quotes render in English
        UUID en = createQuote("en");
        mvc.perform(get("/public/quotes/{t}", tokenOf(en))).andExpect(content().string(containsString("Accept the quote")));
    }

    @Test
    void acceptingWinsTheLeadOnceAndIsIdempotent() throws Exception {
        UUID id = createQuote("fr");
        String token = tokenOf(id);
        long eventsBefore = sse.lastEventId();

        mvc.perform(post("/public/quotes/{t}/accept", token).header("Origin", SAME_ORIGIN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("fullName", "Marie Tremblay").param("agree", "yes"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/public/quotes/" + token));

        Quote q = quoteRepository.findById(id).orElseThrow();
        assertThat(q.getStatus()).isEqualTo("accepted");
        assertThat(q.getAcceptedName()).isEqualTo("Marie Tremblay");
        assertThat(q.getAcceptedAt()).isNotNull();
        assertThat(q.getAcceptedIpHash()).hasSize(64).doesNotContain("127.0.0.1");
        assertThat(businessRepository.findById(lead.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.WON);
        // the won lead becomes a client, priced and scheduled from the accepted quote (P6 hook on the same status path)
        List<Client> clients = clientRepository.search(null, lead.getId());
        assertThat(clients).hasSize(1);
        assertThat(clients.get(0).getFrequency()).isEqualTo("weekly");
        assertThat(clients.get(0).getService()).isEqualTo("Entretien résidentiel");
        assertThat(clients.get(0).getPricePerVisit()).isEqualByComparingTo(q.getSubtotal());
        long notes = contactRepository.findByBusinessIdOrderByContactDateDesc(lead.getId()).stream()
                .filter(c -> c.getContactType() == Contact.ContactType.NOTE && c.getNotes().contains("Marie Tremblay")).count();
        assertThat(notes).isEqualTo(1);
        assertThat(sse.eventsAfter(eventsBefore)).anyMatch(e -> e.type().equals("quote.accepted")
                && e.json().contains(lead.getId().toString()) && e.json().contains("\"total\":147"));

        // the creator is notified
        mvc.perform(get("/api/notifications").with(QUINN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind=='quote_accepted')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.kind=='quote_accepted')].leadId").value(lead.getId().toString()));

        // second accept (double click): same page, no new history, no second event
        long eventsAfter = sse.lastEventId();
        mvc.perform(post("/public/quotes/{t}/accept", token).header("Origin", SAME_ORIGIN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("fullName", "Autre Personne").param("agree", "yes"))
                .andExpect(status().isSeeOther());
        assertThat(quoteRepository.findById(id).orElseThrow().getAcceptedName()).isEqualTo("Marie Tremblay");
        assertThat(sse.lastEventId()).isEqualTo(eventsAfter);
        assertThat(clientRepository.search(null, lead.getId())).hasSize(1);
        assertThat(contactRepository.findByBusinessIdOrderByContactDateDesc(lead.getId()).stream()
                .filter(c -> c.getContactType() == Contact.ContactType.NOTE).count()).isEqualTo(1);

        mvc.perform(get("/public/quotes/{t}", token)).andExpect(content().string(containsString("Soumission acceptée")))
                .andExpect(content().string(not(containsString("Accepter la soumission"))));
        // a decline after acceptance changes nothing
        mvc.perform(post("/public/quotes/{t}/decline", token).header("Origin", SAME_ORIGIN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("reason", "x")).andExpect(status().isSeeOther());
        assertThat(quoteRepository.findById(id).orElseThrow().getStatus()).isEqualTo("accepted");
    }

    @Test
    void acceptNeedsANameTheCheckboxAndASameOriginPost() throws Exception {
        UUID id = createQuote("fr");
        String token = tokenOf(id);
        mvc.perform(post("/public/quotes/{t}/accept", token).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fullName", "Marie Tremblay"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("cocher la case")));
        mvc.perform(post("/public/quotes/{t}/accept", token).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", " ").param("agree", "yes")).andExpect(status().isBadRequest());
        // cross-site form post
        mvc.perform(post("/public/quotes/{t}/accept", token).header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("fullName", "Marie Tremblay").param("agree", "yes"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/public/quotes/{t}/accept", token).header("Origin", "null")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("fullName", "Marie Tremblay").param("agree", "yes"))
                .andExpect(status().isForbidden());
        // the same site is accepted
        mvc.perform(post("/public/quotes/{t}/accept", token).header("Origin", SAME_ORIGIN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("fullName", "Marie Tremblay").param("agree", "yes"))
                .andExpect(status().isSeeOther());
        assertThat(quoteRepository.findById(id).orElseThrow().getStatus()).isEqualTo("accepted");
        mvc.perform(post("/public/quotes/{t}/accept", "B".repeat(43)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", "Marie Tremblay").param("agree", "yes")).andExpect(status().isNotFound());
    }

    @Test
    void expiredAndDeclinedQuotesCannotBeAccepted() throws Exception {
        UUID expiredId = createQuote("fr");
        Quote expired = quoteRepository.findById(expiredId).orElseThrow();
        expired.setValidUntil(java.time.LocalDate.now().minusDays(1));
        quoteRepository.save(expired);
        String t1 = expired.getToken();
        mvc.perform(get("/public/quotes/{t}", t1)).andExpect(status().isOk())
                .andExpect(content().string(containsString("expirée")))
                .andExpect(content().string(not(containsString("Accepter la soumission"))));
        mvc.perform(post("/public/quotes/{t}/accept", t1).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", "Marie Tremblay").param("agree", "yes")).andExpect(status().isSeeOther());
        assertThat(quoteRepository.findById(expiredId).orElseThrow().getStatus()).isEqualTo("expired");
        assertThat(businessRepository.findById(lead.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.INTERESTED);
        mvc.perform(post("/api/quotes/{id}/send", expiredId).with(QUINN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        UUID declinedId = createQuote("fr");
        String t2 = tokenOf(declinedId);
        mvc.perform(post("/public/quotes/{t}/decline", t2).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("reason", "Trop cher")).andExpect(status().isSeeOther());
        Quote declined = quoteRepository.findById(declinedId).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo("declined");
        assertThat(declined.getDeclineReason()).isEqualTo("Trop cher");
        mvc.perform(post("/public/quotes/{t}/accept", t2).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("fullName", "Marie Tremblay").param("agree", "yes")).andExpect(status().isSeeOther());
        assertThat(quoteRepository.findById(declinedId).orElseThrow().getStatus()).isEqualTo("declined");
        assertThat(businessRepository.findById(lead.getId()).orElseThrow().getLeadStatus()).isEqualTo(LeadStatus.INTERESTED);
        mvc.perform(get("/api/leads/{id}/quotes", lead.getId()).with(QUINN))
                .andExpect(jsonPath("$[?(@.id=='" + expiredId + "')].status").value("expired"));
    }

    // ------------------------------------------------------------------ availability and visits (CRM)

    @Test
    void availabilityIsReadByAnyoneAndEditedByAdminsOnly() throws Exception {
        mvc.perform(get("/api/availability").with(VIEWER)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].weekday").value(1)).andExpect(jsonPath("$[0].startTime").value("09:00"));
        String body = "[{\"weekday\":2,\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"slotMinutes\":30,\"active\":true}]";
        mvc.perform(put("/api/availability").with(QUINN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/availability").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].slotMinutes").value(30));
        mvc.perform(put("/api/availability").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"weekday\":2,\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"slotMinutes\":30,\"active\":true},"
                                + "{\"weekday\":2,\"startTime\":\"11:00\",\"endTime\":\"13:00\",\"slotMinutes\":30,\"active\":true}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/availability").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"weekday\":9,\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"slotMinutes\":30,\"active\":true}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/availability").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"weekday\":2,\"startTime\":\"12:00\",\"endTime\":\"10:00\",\"slotMinutes\":30,\"active\":true}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/availability").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"weekday\":2,\"startTime\":\"xx\",\"endTime\":\"10:00\",\"slotMinutes\":30,\"active\":true}]"))
                .andExpect(status().isBadRequest());
        // an empty list closes the calendar: the public page then says no time is available
        mvc.perform(put("/api/availability").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        String url = createLink();
        mvc.perform(get(path(url))).andExpect(content().string(containsString("Aucune plage")));
    }

    @Test
    void bookingLinkFlowBooksOneVisitNotifiesAndInvites() throws Exception {
        MvcResult linkRes = mvc.perform(post("/api/leads/{id}/booking-links", lead.getId()).with(VIEWER))
                .andExpect(status().isForbidden()).andReturn();
        linkRes = mvc.perform(post("/api/leads/{id}/booking-links", lead.getId()).with(QUINN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(matchesPattern("https://api\\.example\\.test/public/book/[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.expiresAt").exists()).andReturn();
        String url = json.readTree(linkRes.getResponse().getContentAsString()).get("url").asText();
        LocalDateTime expires = LocalDateTime.parse(json.readTree(linkRes.getResponse().getContentAsString()).get("expiresAt").asText());
        assertThat(expires).isAfter(LocalDateTime.now().plusDays(13)).isBefore(LocalDateTime.now().plusDays(15));

        MvcResult page = mvc.perform(get(path(url)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
                .andExpect(content().string(containsString("Réservez votre visite")))
                .andReturn();
        String html = page.getResponse().getContentAsString();
        assertThat(html).doesNotContain("<b>Soleil</b>");
        List<String> slots = slotsIn(html);
        assertThat(slots).isNotEmpty();
        LocalDateTime earliest = LocalDateTime.now().plusHours(24);
        LocalDateTime latest = LocalDateTime.now().toLocalDate().plusDays(15).atStartOfDay();
        for (String s : slots) {
            LocalDateTime t = LocalDateTime.parse(s);
            assertThat(t).isAfterOrEqualTo(earliest).isBefore(latest);
            assertThat(t.getDayOfWeek().getValue()).isBetween(1, 5);
            assertThat(t.getHour()).isBetween(9, 16);
        }
        String slot = slots.get(0);
        long eventsBefore = sse.lastEventId();

        mvc.perform(post(path(url)).header("Origin", SAME_ORIGIN).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", slot))
                .andExpect(status().isSeeOther());

        assertThat(visitRepository.findAll()).hasSize(1);
        var visit = visitRepository.findAll().get(0);
        assertThat(visit.getStartsAt()).isEqualTo(LocalDateTime.parse(slot));
        assertThat(visit.getEndsAt()).isEqualTo(LocalDateTime.parse(slot).plusHours(1));
        assertThat(visit.getStatus()).isEqualTo("booked");
        assertThat(visit.getBookedVia()).isEqualTo("link");
        Business refreshed = businessRepository.findById(lead.getId()).orElseThrow();
        assertThat(refreshed.getNextFollowUpAt()).isEqualTo(LocalDateTime.parse(slot));
        assertThat(contactRepository.findByBusinessIdOrderByContactDateDesc(lead.getId()))
                .anyMatch(c -> c.getContactType() == Contact.ContactType.NOTE && c.getNotes().contains("Visite réservée"));
        assertThat(sse.eventsAfter(eventsBefore)).anyMatch(e -> e.type().equals("visit.booked")
                && e.json().contains("\"startsAt\":\"" + slot) && e.json().contains(lead.getId().toString()));
        // invites: the prospect and the link creator
        ArgumentCaptor<MimeMessage> mails = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(2)).send(mails.capture());
        assertThat(mails.getAllValues()).extracting(m -> m.getAllRecipients()[0].toString())
                .containsExactlyInAnyOrder("client@example.com", "quinn@example.com");
        assertThat(attachmentNames(mails.getAllValues().get(0))).contains("visite.ics");

        mvc.perform(get("/api/notifications").with(QUINN))
                .andExpect(jsonPath("$[?(@.kind=='visit_booked')]", hasSize(1)));

        // the page now confirms, a second post with the same link is refused, the slot is gone for others
        mvc.perform(get(path(url))).andExpect(content().string(containsString("Votre visite est réservée")));
        mvc.perform(post(path(url)).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", slots.get(1)))
                .andExpect(status().isConflict());
        assertThat(visitRepository.findAll()).hasSize(1);
        String second = createLink();
        mvc.perform(post(path(second)).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", slot))
                .andExpect(status().isConflict()).andExpect(content().string(containsString("vient d&#39;être prise")));
        mvc.perform(get(path(second))).andExpect(content().string(not(containsString("value=\"" + slot + "\""))));
        assertThat(visitRepository.findAll()).hasSize(1);

        // CRM list and status change
        String day = LocalDateTime.parse(slot).toLocalDate().toString();
        MvcResult list = mvc.perform(get("/api/visits").param("from", day).param("to", day).with(QUINN))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].leadName").value("Clinique <b>Soleil</b>"))
                .andExpect(jsonPath("$[0].status").value("booked")).andReturn();
        String visitId = json.readTree(list.getResponse().getContentAsString()).get(0).get("id").asText();
        mvc.perform(get("/api/visits").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/visits/{id}", visitId).with(VIEWER).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"done\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/visits/{id}", visitId).with(QUINN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"weird\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/visits/{id}", visitId).with(QUINN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        mvc.perform(get("/api/visits").param("from", day).param("to", day).param("status", "x").with(QUINN))
                .andExpect(jsonPath("$[0].status").value("cancelled"));
        mvc.perform(get("/api/visits").param("from", "2026-02-01").param("to", "2026-01-01").with(QUINN)).andExpect(status().isBadRequest());
    }

    @Test
    void bookingRejectsBadSlotsExpiredLinksAndAcceptsJson() throws Exception {
        String url = createLink();
        String path = path(url);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", "garbage")).andExpect(status().isBadRequest());
        // not an offered slot (3 a.m. next Monday-Friday)
        LocalDateTime night = LocalDateTime.now().plusDays(3).withHour(3).withMinute(0).withSecond(0).withNano(0);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", night.toString().substring(0, 16)))
                .andExpect(status().isConflict());
        // inside the 24 h notice
        LocalDateTime soon = LocalDateTime.now().plusHours(2).withMinute(0).withSecond(0).withNano(0);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", soon.toString().substring(0, 16)))
                .andExpect(status().isConflict());
        mvc.perform(post("/public/book/{t}", "C".repeat(43)).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", "2030-01-01T10:00"))
                .andExpect(status().isNotFound());
        assertThat(visitRepository.count()).isZero();

        String slot = slotsIn(mvc.perform(get(path)).andReturn().getResponse().getContentAsString()).get(2);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"slot\":\"" + slot + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.startsAt").value(slot + ":00"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"slot\":\"" + slot + "\"}"))
                .andExpect(status().isGone());

        String expiredUrl = createLink();
        BookingLink link = linkRepository.findAll().stream().filter(l -> expiredUrl.endsWith(l.getToken())).findFirst().orElseThrow();
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        linkRepository.save(link);
        mvc.perform(get(path(expiredUrl))).andExpect(content().string(containsString("expiré")));
        mvc.perform(post(path(expiredUrl)).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("slot", slot))
                .andExpect(status().isConflict());
    }

    @Test
    void emailTemplatesRenderLinkPlaceholdersOnlyWhenPresent() throws Exception {
        templateRepository.deleteAll();
        EmailTemplate t = new EmailTemplate();
        t.setKey("intro");
        t.setLanguage("fr");
        t.setSubject("Bonjour");
        t.setBody("Allo,\n\nRéserver une visite : {{bookingLink}}\nVotre soumission : {{quoteLink}}\n\nMerci");
        templateRepository.save(t);
        String req = "{\"templateKey\":\"intro\",\"language\":\"fr\"}";

        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(QUINN).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(not(containsString("Réserver"))))
                .andExpect(jsonPath("$.body").value(not(containsString("{{"))))
                .andExpect(jsonPath("$.body").value(containsString("Merci")));

        String booking = createLink();
        createQuote("fr");
        mvc.perform(post("/api/leads/{id}/emails/preview", lead.getId()).with(QUINN).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(jsonPath("$.body").value(containsString("Réserver une visite : " + booking)))
                .andExpect(jsonPath("$.body").value(containsString("Votre soumission : https://api.example.test/public/quotes/")));
    }

    // ------------------------------------------------------------------ helpers

    UUID createQuote(String language) throws Exception {
        MvcResult res = mvc.perform(post("/api/leads/{id}/quotes", lead.getId()).with(QUINN)
                        .contentType(MediaType.APPLICATION_JSON).content(ESTIMATE.replace("\"fr\"", "\"" + language + "\"")))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    String tokenOf(UUID quoteId) {
        return quoteRepository.findById(quoteId).orElseThrow().getToken();
    }

    String createLink() throws Exception {
        MvcResult res = mvc.perform(post("/api/leads/{id}/booking-links", lead.getId()).with(QUINN))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("url").asText();
    }

    static String path(String url) {
        return url.substring(url.indexOf("/public/"));
    }

    static List<String> slotsIn(String html) {
        Matcher m = Pattern.compile("name=\"slot\" value=\"([0-9T:-]+)\"").matcher(html);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String textOf(MimeMessage message) throws Exception {
        Object content = message.getContent();
        StringBuilder sb = new StringBuilder();
        collect(content, sb);
        return sb.toString();
    }

    private static void collect(Object content, StringBuilder sb) throws Exception {
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                if (part.getFileName() == null) collect(part.getContent(), sb);
            }
        } else if (content instanceof String s) {
            sb.append(s);
        }
    }

    static List<String> attachmentNames(MimeMessage message) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        Object content = message.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String name = mp.getBodyPart(i).getFileName();
                if (name != null) names.add(name);
            }
        }
        return names;
    }

    Business newLead(String name, String email, LeadStatus status) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Clinique");
        b.setDataSource("TEST");
        b.setLeadStatus(status);
        b.setEmail(email);
        b.setAddressStreet("123 rue Principale");
        b.setAddressCity("Montréal");
        b.setPhone("5145550123");
        return businessRepository.save(b);
    }

    User ensureUser(String username, User.UserRole role) {
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
