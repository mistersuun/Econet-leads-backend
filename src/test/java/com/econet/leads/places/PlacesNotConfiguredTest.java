package com.econet.leads.places;

import com.econet.leads.model.Business;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without GOOGLE_PLACES_API_KEY the app starts, reports "not configured" and enrichment answers 503. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlacesNotConfiguredTest {

    @Autowired MockMvc mvc;
    @Autowired BusinessRepository businessRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void statusSaysNotConfiguredAndEnrichmentIs503() throws Exception {
        if (userRepository.findByUsername("pierre").isEmpty()) {
            User u = new User();
            u.setUsername("pierre");
            u.setEmail("pierre@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        Business b = new Business();
        b.setBusinessName("Sans Google inc.");
        b.setBusinessType("Bureau");
        b.setDataSource("Manuel");
        b = businessRepository.save(b);

        mvc.perform(get("/api/places/status").with(user("pierre").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.usedToday").value(0));
        mvc.perform(post("/api/businesses/" + b.getId() + "/enrich/places").with(user("pierre").roles("USER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", containsString("GOOGLE_PLACES_API_KEY")));
        mvc.perform(post("/api/businesses/enrich/places").with(user("pierre").roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + b.getId() + "\"]}"))
                .andExpect(status().isServiceUnavailable());
    }
}
