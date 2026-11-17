package com.econet.leads.clients;

import com.econet.leads.model.Business;
import com.econet.leads.model.Client;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ClientRepository;
import com.econet.leads.model.User;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Without GOOGLE_REVIEW_URL and SMTP the review request answers 503 with the missing variable names. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientsNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessRepository businessRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;

    @AfterEach
    void cleanup() {
        clientRepository.deleteAll();
        contactRepository.deleteAll();
        businessRepository.deleteAll();
    }

    @Test
    void reviewRequestReportsNotConfigured() throws Exception {
        if (userRepository.findByUsername("alice").isEmpty()) {
            User u = new User();
            u.setUsername("alice");
            u.setEmail("alice@example.com");
            u.setPasswordHash("x");
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        Business b = new Business();
        b.setBusinessName("Resto Sans Config");
        b.setBusinessType("Restaurant");
        b.setDataSource("TEST");
        b.setLeadStatus(LeadStatus.WON);
        b = businessRepository.save(b);
        Client c = new Client();
        c.setBusiness(b);
        c.setService("Entretien ménager");
        c.setPricePerVisit(BigDecimal.TEN);
        c.setStartDate(LocalDate.now());
        c = clientRepository.save(c);

        mvc.perform(get("/api/clients/review-config").with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleReviewUrl").value(false))
                .andExpect(jsonPath("$.smtp").value(false));
        for (String channel : new String[]{"link", "email"}) {
            mvc.perform(post("/api/clients/{id}/review-request", c.getId()).with(user("alice").roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"" + channel + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value(containsString("GOOGLE_REVIEW_URL")));
        }
    }
}
