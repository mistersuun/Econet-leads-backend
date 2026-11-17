package com.econet.leads.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrenchErrorsApiTest {

    @Autowired MockMvc mvc;

    @Test
    void loginFailureIsFrench() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"personne\",\"password\":\"mauvais-mot-de-passe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Nom d'utilisateur ou mot de passe invalide"));
    }

    @Test
    void validationMessagesAreFrenchAndKeepTheShape() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation échouée"))
                .andExpect(jsonPath("$.fields.username").value("Le nom d'utilisateur est obligatoire"))
                .andExpect(jsonPath("$.fields.password").value("Le mot de passe est obligatoire"));
    }

    @Test
    void genericStatusBodiesAreFrench() throws Exception {
        mvc.perform(get("/api/leads/not-a-uuid").with(user("x").roles("USER")))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error", not(containsString("Invalid"))));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Requête mal formée"));
        mvc.perform(get("/api/users").with(user("x").roles("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Accès refusé"));
        mvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Non autorisé"));
    }
}
