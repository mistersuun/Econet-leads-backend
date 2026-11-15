package com.econet.leads.controller;

import com.econet.leads.model.User;
import com.econet.leads.report.WeeklyReportScheduler;
import com.econet.leads.report.WeeklyReportService;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without SMTP the weekly report still previews; sending says "not configured" and the scheduler skips. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeeklyReportNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired WeeklyReportService service;
    @Autowired WeeklyReportScheduler scheduler;

    @Test
    void sendNowIs503ButPreviewWorks() throws Exception {
        if (userRepository.findByUsername("p4erin").isEmpty()) {
            User u = new User();
            u.setUsername("p4erin");
            u.setEmail("p4erin@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        assertThat(service.mailConfigured()).isFalse();
        mvc.perform(post("/api/reports/weekly/send-now").with(user("admin").roles("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/reports/weekly/preview").with(user("p4erin").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").exists());
        scheduler.run(); // must not throw
        assertThatThrownBy(service::sendAll).hasMessageContaining("non configuré");
    }
}
