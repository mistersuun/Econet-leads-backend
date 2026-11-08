package com.econet.leads.leads;

import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Nothing listens on the configured Overpass port: the API must answer a clean 502 {error}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.discover.overpass-url=http://127.0.0.1:9/api/interpreter",
        "app.discover.nominatim-url=http://127.0.0.1:9",
        "app.discover.min-interval-ms=0",
        "app.discover.timeout-seconds=3"
})
class OsmDiscoverUnreachableTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");

    @Test
    void unreachableServiceIsAClean502() throws Exception {
        if (userRepository.findByUsername("alice").isEmpty()) {
            User u = new User();
            u.setUsername("alice");
            u.setEmail("alice@example.com");
            u.setPasswordHash(passwordEncoder.encode("pw-" + UUID.randomUUID()));
            u.setRole(User.UserRole.USER);
            u.setActive(true);
            userRepository.save(u);
        }
        mvc.perform(post("/api/discover/osm").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Montréal\",\"categories\":[\"restaurant\"]}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error", containsString("indisponible")))
                .andExpect(content().string(not(containsString("ConnectException"))))
                .andExpect(content().string(not(containsString("at com.econet"))));
    }
}
