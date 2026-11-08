package com.econet.leads.leads;

import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Addendum 4 A: POST /api/businesses as "Ajouter un lead" and GET /api/businesses/duplicates. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManualLeadApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

    User alice;

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        alice = ensureUser("alice", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);
    }

    @Test
    void minimalLeadGetsDefaultsManualSourceAssigneeAndNote() throws Exception {
        String body = """
                {"businessName":"Café du Coin","phone":"514 555-0142","addressCity":"Montréal",
                 "leadSource":"référence","notes":"Parler à Marie, ouvre à 7 h",
                 "qualification":{"decisionMaker":"Marie","language":"fr"}}""";
        JsonNode created = json.readTree(mvc.perform(post("/api/businesses").with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessType").value("Autre"))
                .andExpect(jsonPath("$.dataSource").value("Manuel"))
                .andExpect(jsonPath("$.leadStatus").value("NEW"))
                .andExpect(jsonPath("$.assignedToName").value("alice"))
                .andExpect(jsonPath("$.phone").value("(514) 555-0142"))
                .andExpect(jsonPath("$.sourceDetails['Ajouté par']").value("alice"))
                .andExpect(jsonPath("$.sourceDetails.Origine").value("référence"))
                .andExpect(jsonPath("$.qualification.decisionMaker").value("Marie"))
                .andReturn().getResponse().getContentAsString());

        List<Contact> contacts = contactRepository.findAll();
        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).getContactType()).isEqualTo(Contact.ContactType.NOTE);
        assertThat(contacts.get(0).getNotes()).isEqualTo("Parler à Marie, ouvre à 7 h");
        assertThat(contacts.get(0).getBusiness().getId().toString()).isEqualTo(created.get("id").asText());
    }

    @Test
    void clientCannotChooseTheDataSourceAndTerminalStatusesAreRefused() throws Exception {
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Chez Paul\",\"dataSource\":\"Autre chose\",\"leadStatus\":\"INTERESTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSource").value("Manuel"))
                .andExpect(jsonPath("$.leadStatus").value("INTERESTED"));
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Chez Pierre\",\"leadStatus\":\"WON\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.businessName").exists());
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Bad qual\",\"qualification\":{\"language\":\"de\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exactDuplicateIs409WithTheExistingLeadUnlessForced() throws Exception {
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"CPE Les Lucioles\",\"addressCity\":\"Laval\",\"phone\":\"450-555-0111\"}"))
                .andExpect(status().isOk());

        // same name + city
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"CPE Les Lucioles\",\"addressCity\":\"Laval\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.duplicate.businessName").value("CPE Les Lucioles"))
                .andExpect(jsonPath("$.duplicate.id").exists());
        // same phone + city, other name
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Garderie X\",\"addressCity\":\"Laval\",\"phone\":\"(450) 555 0111\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.duplicate.businessName").value("CPE Les Lucioles"));
        // same phone, different city: a different branch, allowed
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"CPE Les Lucioles\",\"addressCity\":\"Longueuil\",\"phone\":\"450-555-0111\"}"))
                .andExpect(status().isOk());
        // force
        mvc.perform(post("/api/businesses").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"CPE Les Lucioles\",\"addressCity\":\"Laval\",\"force\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").exists());
        assertThat(businessRepository.count()).isEqualTo(3);
    }

    @Test
    void duplicatesEndpointReturnsUpToFiveHintsByNamePhoneAndFuzzyName() throws Exception {
        for (int i = 0; i < 7; i++) {
            save("Clinique Soleil " + i, "Montréal", "514555020" + i);
        }
        save("Restaurant Le Vieux Port", "Québec", "4185550300");

        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("name", "Restaurant Le Vieux Port").param("city", "Québec"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessName").value("Restaurant Le Vieux Port"));
        // name only: any city, case-insensitive
        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("name", "restaurant le vieux port"))
                .andExpect(jsonPath("$", hasSize(1)));
        // phone + city
        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("phone", "(418) 555-0300").param("city", "Québec"))
                .andExpect(jsonPath("$", hasSize(1)));
        // fuzzy while typing a near name
        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("name", "Restaurant Le Vieux Porrt").param("city", "Québec"))
                .andExpect(jsonPath("$", hasSize(1)));
        // capped at 5
        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("name", "Clinique Soleil 3").param("city", "Montréal"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(5))));
        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("name", "Clinique Soleil 0").param("city", "Montréal"))
                .andExpect(jsonPath("$", hasSize(5)));
        mvc.perform(get("/api/businesses/duplicates").with(ALICE)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/businesses/duplicates").with(ALICE).param("name", "Totalement Inconnu"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void viewerCannotAddLeadsNorUseTheDuplicateHints() throws Exception {
        mvc.perform(post("/api/businesses").with(VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Nope\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/businesses/duplicates").with(VIEWER).param("name", "Nope"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/businesses").contentType(MediaType.APPLICATION_JSON).content("{\"businessName\":\"Nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    private void save(String name, String city, String phone) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Clinique");
        b.setAddressCity(city);
        b.setPhone(phone);
        b.setPhoneNormalized(phone);
        b.setDataSource("Test");
        businessRepository.save(b);
    }

    private User ensureUser(String username, User.UserRole role) {
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
