package com.econet.leads.sms;

import com.econet.leads.integration.website.InboundLeadEvent;
import com.econet.leads.model.Business;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Nothing configured: the app starts, status says so, sends are 503, alerts are silent no-ops. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SmsNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessRepository businessRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SmsMessageRepository smsMessages;
    @Autowired AlertService alertService;

    @Test
    void everythingReportsNotConfigured() throws Exception {
        if (userRepository.findByUsername("nosms").isEmpty()) {
            User u = new User();
            u.setUsername("nosms");
            u.setEmail("nosms@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        Business b = new Business();
        b.setBusinessName("Sans Twilio");
        b.setBusinessType("CPE");
        b.setDataSource("TEST");
        b.setLeadStatus(LeadStatus.NEW);
        b.setPhone("514-555-1111");
        b = businessRepository.save(b);

        mvc.perform(get("/api/integrations/status").with(user("nosms").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sms").value(false))
                .andExpect(jsonPath("$.smtp").value(false))
                .andExpect(jsonPath("$.alertsPhone").value(false))
                .andExpect(jsonPath("$.alertsEmail").value(false))
                .andExpect(jsonPath("$.crmUrl").value("http://localhost:5173"));
        mvc.perform(post("/api/leads/{id}/sms", b.getId()).with(user("nosms").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateKey\":\"missed_call\",\"consent\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("TWILIO_ACCOUNT_SID")));
        mvc.perform(post("/api/integrations/test-alert").with(user("admin").roles("ADMIN")))
                .andExpect(status().isServiceUnavailable());

        long before = smsMessages.count();
        alertService.handle(new InboundLeadEvent(b.getId(), "Marie", "booking", "Demande web", null, "514-555-1111"));
        assertThat(smsMessages.count()).isEqualTo(before);
    }
}
