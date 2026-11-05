package com.econet.leads.controller;

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
import org.springframework.mail.javamail.JavaMailSender;
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

/** Without SMTP_HOST there is no JavaMailSender bean: status says so and sends return 503. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired BusinessRepository businessRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void statusReportsNotConfiguredAndSendIs503() throws Exception {
        assertThat(context.getBeanNamesForType(JavaMailSender.class)).isEmpty();
        if (userRepository.findByUsername("dora").isEmpty()) {
            User u = new User();
            u.setUsername("dora");
            u.setEmail("dora@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        Business b = new Business();
        b.setBusinessName("Sans SMTP");
        b.setBusinessType("CPE");
        b.setDataSource("TEST");
        b.setLeadStatus(LeadStatus.NEW);
        b.setEmail("x@example.com");
        b = businessRepository.save(b);

        mvc.perform(get("/api/email/status").with(user("dora").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
        mvc.perform(post("/api/leads/{id}/emails", b.getId()).with(user("dora").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"templateKey\":\"intro\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }
}
