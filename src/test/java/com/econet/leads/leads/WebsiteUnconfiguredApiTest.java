package com.econet.leads.leads;

import com.econet.leads.model.DataSource;
import com.econet.leads.repository.DataSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Without WEBSITE_DB_URL: the website source reports configured=false and refuses imports with a clear 400. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebsiteUnconfiguredApiTest {

    static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired MockMvc mvc;
    @Autowired DataSourceRepository dataSourceRepository;

    @Test
    void websiteSourceIsNotConfiguredAndOthersAre() throws Exception {
        DataSource website = dataSourceRepository.findBySourceName("Site web – demandes").orElseGet(DataSource::new);
        website.setSourceName("Site web – demandes");
        website.setSourceType(DataSource.SourceType.WEBSITE_DB);
        website.setActive(false);
        website.setConfig(Map.of("importer", "WEBSITE_REQUESTS"));
        website = dataSourceRepository.save(website);
        DataSource other = dataSourceRepository.findBySourceName("Manuel").orElseGet(DataSource::new);
        other.setSourceName("Manuel");
        other.setSourceType(DataSource.SourceType.MANUAL);
        other.setActive(false);
        dataSourceRepository.save(other);

        mvc.perform(get("/api/data-sources").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sourceName=='Site web – demandes')].configured", contains(false)))
                .andExpect(jsonPath("$[?(@.sourceName=='Site web – demandes')].active", contains(false)))
                .andExpect(jsonPath("$[?(@.sourceName=='Manuel')].configured", contains(true)));
        mvc.perform(get("/api/data-sources/{id}", website.getId()).with(ADMIN)).andExpect(jsonPath("$.configured").value(false));

        mvc.perform(post("/api/data-sources/{id}/import", website.getId()).with(ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("WEBSITE_DB_URL")));
        // also when the admin switched it on by hand: the clear message, not a failed job
        website.setActive(true);
        dataSourceRepository.save(website);
        mvc.perform(post("/api/data-sources/{id}/import", website.getId()).with(ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("WEBSITE_DB_URL")));
        mvc.perform(post("/api/data-sources/import/{name}", "Site web – demandes").with(ADMIN))
                .andExpect(status().isBadRequest());
    }
}
