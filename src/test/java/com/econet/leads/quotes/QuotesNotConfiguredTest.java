package com.econet.leads.quotes;

import com.econet.leads.model.Business;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BookingLinkRepository;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.QuoteRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.repository.VisitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No SMTP, no APP_PUBLIC_URL: quotes and booking links still work (links derived from the request),
 * sending a quote is a 503, and the public pages are rate limited per client address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.public.rate-limit-per-minute=8")
class QuotesNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired BusinessRepository businessRepository;
    @Autowired UserRepository userRepository;
    @Autowired QuoteRepository quoteRepository;
    @Autowired VisitRepository visitRepository;
    @Autowired BookingLinkRepository linkRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        linkRepository.deleteAll();
        visitRepository.deleteAll();
        quoteRepository.deleteAll();
        businessRepository.deleteAll();
    }

    @Test
    void sendIs503WithoutSmtpButEverythingElseWorksAndPublicPagesAreRateLimited() throws Exception {
        assertThat(context.getBeanNamesForType(JavaMailSender.class)).isEmpty();
        if (userRepository.findByUsername("nora").isEmpty()) {
            User u = new User();
            u.setUsername("nora");
            u.setEmail("nora@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        Business b = new Business();
        b.setBusinessName("Sans courriel");
        b.setBusinessType("CPE");
        b.setDataSource("TEST");
        b.setLeadStatus(LeadStatus.NEW);
        b.setEmail("x@example.com");
        b = businessRepository.save(b);

        MvcResult created = mvc.perform(post("/api/leads/{id}/quotes", b.getId()).with(user("nora").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimate\":{\"service\":\"commercial\",\"area\":\"800_1500\",\"propertyType\":\"office\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.publicUrl").value(containsString("http://localhost/public/quotes/")))
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/quotes/{id}/send", id).with(user("nora").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());
        assertThat(quoteRepository.findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo("draft");

        mvc.perform(post("/api/leads/{id}/booking-links", b.getId()).with(user("nora").roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(containsString("http://localhost/public/book/")));

        // 8 requests per minute per address: the 9th is refused, another address is not
        for (int i = 0; i < 8; i++) {
            mvc.perform(get("/public/quotes/{t}", "A".repeat(43)).with(from("203.0.113.7")))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/public/quotes/{t}", "A".repeat(43)).with(from("203.0.113.7")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
                .andExpect(content().string(containsString("Trop de requêtes")));
        mvc.perform(get("/public/quotes/{t}", "A".repeat(43)).with(from("203.0.113.8")))
                .andExpect(status().isNotFound());
        // the CRM API is not affected by the public limit
        mvc.perform(get("/api/availability").with(user("nora").roles("USER"))).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
