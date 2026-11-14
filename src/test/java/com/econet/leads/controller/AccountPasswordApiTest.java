package com.econet.leads.controller;

import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountPasswordApiTest {

    static final RequestPostProcessor PAULA = user("paula.pw").roles("USER");
    static final RequestPostProcessor VIC = user("vic.pw").roles("VIEWER");
    static final RequestPostProcessor ADA = user("ada.pw").roles("ADMIN");

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    User paula;

    @BeforeEach
    void seed() {
        paula = ensure("paula.pw", User.UserRole.USER, "ancien-mot-de-passe");
        paula.setPasswordHash(encoder.encode("ancien-mot-de-passe"));
        users.save(paula);
        ensure("vic.pw", User.UserRole.VIEWER, "viewer-password-1");
        ensure("ada.pw", User.UserRole.ADMIN, "admin-password-1");
    }

    User ensure(String username, User.UserRole role, String pw) {
        return users.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@example.com");
            u.setPasswordHash(encoder.encode(pw));
            u.setRole(role);
            u.setActive(true);
            return users.save(u);
        });
    }

    String body(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    @Test
    void changesOwnPassword() throws Exception {
        mvc.perform(post("/api/me/password").with(PAULA).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ancien-mot-de-passe", "nouveau-mot-de-passe-1")))
                .andExpect(status().isNoContent());
        assertThat(encoder.matches("nouveau-mot-de-passe-1", users.findByUsername("paula.pw").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void wrongCurrentPasswordIs400InFrench() throws Exception {
        mvc.perform(post("/api/me/password").with(PAULA).contentType(MediaType.APPLICATION_JSON)
                        .content(body("mauvais", "nouveau-mot-de-passe-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("incorrect")));
    }

    @Test
    void tooShortOrEqualToUsernameIs400() throws Exception {
        mvc.perform(post("/api/me/password").with(PAULA).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ancien-mot-de-passe", "court")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("10")));
        // exactly the username, padded to be long enough on another account
        User u = ensure("longusername-x", User.UserRole.USER, "ancien-mot-de-passe");
        mvc.perform(post("/api/me/password").with(user("longusername-x").roles("USER")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ancien-mot-de-passe", "longusername-x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("nom d'utilisateur")));
    }

    @Test
    void viewerMayChangeOwnPasswordAndAnonymousMayNot() throws Exception {
        mvc.perform(post("/api/me/password").with(VIC).contentType(MediaType.APPLICATION_JSON)
                        .content(body("viewer-password-1", "viewer-password-2")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(body("a", "b")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingFieldsAreFrenchValidationErrors() throws Exception {
        mvc.perform(post("/api/me/password").with(PAULA).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation échouée"))
                .andExpect(jsonPath("$.fields.currentPassword").value("Le mot de passe actuel est obligatoire"));
    }

    @Test
    void adminResetsAnotherUsersPassword() throws Exception {
        mvc.perform(put("/api/users/" + paula.getId() + "/password").with(ADA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"reinitialise-par-admin\"}"))
                .andExpect(status().isNoContent());
        assertThat(encoder.matches("reinitialise-par-admin", users.findByUsername("paula.pw").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void resetIsAdminOnlyAndValidated() throws Exception {
        mvc.perform(put("/api/users/" + paula.getId() + "/password").with(PAULA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"reinitialise-par-admin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Accès refusé"));
        mvc.perform(put("/api/users/" + paula.getId() + "/password").with(ADA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"court\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/users/00000000-0000-0000-0000-000000000000/password").with(ADA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"reinitialise-par-admin\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("introuvable")));
    }
}
