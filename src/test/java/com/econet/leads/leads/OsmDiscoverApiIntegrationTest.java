package com.econet.leads.leads;

import com.econet.leads.model.Business;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Addendum 4 C against a local stand-in for Overpass/Nominatim serving the recorded fixture
 * (the real hosts are not reachable from the build sandbox).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OsmDiscoverApiIntegrationTest {

    static final HttpServer SERVER;
    static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    static final List<String> USER_AGENTS = new CopyOnWriteArrayList<>();
    static final List<String> NOMINATIM = new CopyOnWriteArrayList<>();

    static {
        try {
            String fixture = Files.readString(Path.of("src/test/resources/fixtures/overpass-montreal.json"));
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            SERVER.createContext("/api/interpreter", ex -> {
                String body = URLDecoder.decode(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                QUERIES.add(body);
                USER_AGENTS.add(ex.getRequestHeaders().getFirst("User-Agent"));
                String answer;
                int status = 200;
                if (body.contains("Villeboom")) {
                    status = 504;
                    answer = "<html>gateway timeout at /srv/overpass/internal/path</html>";
                } else if (body.contains("area[\"name\"=\"Sainte-Introuvable\"]")) {
                    answer = "{\"elements\":[]}";
                } else if (body.contains("area.a") || body.contains("(45.")) {
                    answer = fixture;
                } else {
                    answer = "{\"elements\":[]}";
                }
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            SERVER.createContext("/search", ex -> {
                NOMINATIM.add(ex.getRequestURI().getRawQuery());
                USER_AGENTS.add(ex.getRequestHeaders().getFirst("User-Agent"));
                byte[] bytes = "[{\"boundingbox\":[\"45.4\",\"45.7\",\"-73.9\",\"-73.4\"],\"display_name\":\"Sainte-Introuvable\"}]".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            SERVER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        r.add("app.discover.overpass-url", () -> base + "/api/interpreter");
        r.add("app.discover.nominatim-url", () -> base);
        r.add("app.discover.min-interval-ms", () -> "0");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BusinessRepository businessRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

    @BeforeEach
    void setUp() {
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        ensureUser("alice", User.UserRole.USER);
        ensureUser("viewer", User.UserRole.VIEWER);
        QUERIES.clear();
        USER_AGENTS.clear();
        NOMINATIM.clear();
    }

    @Test
    void searchMapsTheFixtureToCandidatesAndFlagsExistingLeads() throws Exception {
        // a lead already in the base: same name + city as fixture node 1001, and another with the phone of node 1003
        lead("Bistro Le Comptoir", "Montréal", "5145550161");
        lead("Pizzeria Ancien Nom", "Montréal", "5145550170");

        JsonNode res = search("{\"city\":\"Montréal\",\"categories\":[\"restaurant\",\"clinic\",\"pharmacy\",\"daycare\",\"retail\",\"senior\"],\"limit\":50}");
        assertThat(res.get("attribution").asText()).isEqualTo("© OpenStreetMap contributors");
        JsonNode candidates = res.get("candidates");
        // 12 fixture elements, one has no name
        assertThat(candidates).hasSize(11);

        JsonNode bistro = byId(candidates, "node/1001");
        assertThat(bistro.get("name").asText()).isEqualTo("Bistro Le Comptoir");
        assertThat(bistro.get("category").asText()).isEqualTo("restaurant");
        assertThat(bistro.get("phone").asText()).isEqualTo("+1 514 555-0161");
        assertThat(bistro.get("addressStreet").asText()).isEqualTo("250 Avenue du Mont-Royal Est");
        assertThat(bistro.get("addressCity").asText()).isEqualTo("Montréal");
        assertThat(bistro.get("postalCode").asText()).isEqualTo("H2T 1P3");
        assertThat(bistro.get("website").asText()).isEqualTo("https://lecomptoir.example");
        assertThat(bistro.get("lat").decimalValue().doubleValue()).isEqualTo(45.5231);
        assertThat(bistro.get("lon").decimalValue().doubleValue()).isEqualTo(-73.5817);
        assertThat(bistro.get("existingLeadId").asText()).isNotBlank().isNotEqualTo("null");
        assertThat(byId(candidates, "node/1003").get("existingLeadId").isNull()).isFalse(); // phone + city match

        JsonNode noPhone = byId(candidates, "node/1002");
        assertThat(noPhone.get("phone").isNull()).isTrue();
        assertThat(noPhone.get("addressCity").asText()).isEqualTo("Montréal"); // searched city when addr:city is missing
        assertThat(noPhone.get("existingLeadId").isNull()).isTrue();

        // way: coordinates come from center; ';' separated phones keep the first; contact:* keys
        JsonNode way = byId(candidates, "way/2001");
        assertThat(way.get("phone").asText()).isEqualTo("514-555-0162");
        assertThat(way.get("email").asText()).isEqualTo("poutine@vieux.example");
        assertThat(way.get("lat").decimalValue().doubleValue()).isEqualTo(45.5088);
        assertThat(byId(candidates, "way/2002").get("category").asText()).isEqualTo("clinic");
        assertThat(byId(candidates, "node/1007").get("category").asText()).isEqualTo("retail");
        assertThat(byId(candidates, "node/1009").get("category").asText()).isEqualTo("senior");
        assertThat(byId(candidates, "way/2003").get("category").asText()).isEqualTo("daycare");

        // the Overpass query: area by name, nodes and ways, out center tags, requested limit
        assertThat(QUERIES).hasSize(1);
        String q = QUERIES.get(0);
        assertThat(q).contains("[timeout:25]").contains("area[\"name\"=\"Montréal\"][\"boundary\"=\"administrative\"]->.a;")
                .contains("node[\"amenity\"~\"^(restaurant|cafe|fast_food|bar)$\"](area.a);")
                .contains("way[\"amenity\"~\"^(restaurant|cafe|fast_food|bar)$\"](area.a);")
                .contains("[\"healthcare\"]").contains("[\"shop\"]").contains("[\"amenity\"=\"pharmacy\"]")
                .contains("[\"amenity\"~\"^(kindergarten|childcare)$\"]")
                .contains("[\"amenity\"=\"social_facility\"][\"social_facility\"~\"^(nursing_home|assisted_living)$\"]")
                .contains("out center tags 50;");
        assertThat(USER_AGENTS).containsOnly("EconetLeads/1.0");
        assertThat(NOMINATIM).isEmpty();
        // candidates are not saved
        assertThat(businessRepository.count()).isEqualTo(2);
    }

    @Test
    void limitIsClampedAndTruncatesTheResults() throws Exception {
        JsonNode res = search("{\"city\":\"Montréal\",\"categories\":[\"restaurant\"],\"limit\":3}");
        assertThat(res.get("candidates")).hasSize(3);
        search("{\"city\":\"Montréal\",\"categories\":[\"restaurant\"],\"limit\":5000}");
        assertThat(QUERIES.get(1)).contains("out center tags 300;");
        search("{\"city\":\"Montréal\",\"categories\":[\"restaurant\"]}");
        assertThat(QUERIES.get(2)).contains("out center tags 100;");
    }

    @Test
    void unknownAreaFallsBackToTheNominatimBoundingBox() throws Exception {
        JsonNode res = search("{\"city\":\"Sainte-Introuvable\",\"categories\":[\"pharmacy\"]}");
        assertThat(res.get("candidates")).isNotEmpty();
        assertThat(NOMINATIM).hasSize(1);
        assertThat(NOMINATIM.get(0)).contains("format=json").contains("Sainte-Introuvable");
        assertThat(QUERIES).hasSize(2);
        assertThat(QUERIES.get(1)).contains("(45.400000,-73.900000,45.700000,-73.400000)").doesNotContain("area[");
    }

    @Test
    void validationErrorsAreClean400s() throws Exception {
        mvc.perform(post("/api/discover/osm").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"categories\":[\"restaurant\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/discover/osm").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Laval\",\"categories\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/discover/osm").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Laval\",\"categories\":[\"casino\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("casino")));
        assertThat(QUERIES).isEmpty();
    }

    @Test
    void upstreamErrorIsA502WithoutLeakingDetails() throws Exception {
        mvc.perform(post("/api/discover/osm").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Villeboom\",\"categories\":[\"restaurant\"]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", containsString("OpenStreetMap")))
                .andExpect(content().string(not(containsString("/srv/overpass"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void importAddsNewLeadsSkipsExistingOnesAndKeepsOsmFacts() throws Exception {
        lead("Bistro Le Comptoir", "Montréal", "5145550161");
        JsonNode res = search("{\"city\":\"Montréal\",\"categories\":[\"restaurant\",\"pharmacy\"],\"limit\":50}");
        List<JsonNode> chosen = new java.util.ArrayList<>();
        res.get("candidates").forEach(chosen::add);
        String body = json.writeValueAsString(Map.of("candidates", chosen, "assignToMe", true));

        JsonNode out = json.readTree(mvc.perform(post("/api/discover/osm/import").with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 11 candidates: 1 already a lead -> 10 added
        assertThat(out.get("added").asInt()).isEqualTo(10);
        assertThat(out.get("skipped").asInt()).isEqualTo(1);
        assertThat(out.get("leads")).hasSize(10);

        Business pharmacie = businessRepository.findAll().stream().filter(b -> b.getBusinessName().equals("Pharmacie Centrale")).findFirst().orElseThrow();
        assertThat(pharmacie.getDataSource()).isEqualTo("OpenStreetMap");
        assertThat(pharmacie.getExternalId()).isEqualTo("node/1006");
        assertThat(pharmacie.getBusinessType()).isEqualTo("Pharmacie");
        assertThat(pharmacie.getSourceDetails()).containsEntry("Catégorie OSM", "amenity=pharmacy").containsEntry("Source", "OpenStreetMap");
        assertThat(pharmacie.getLatitude().doubleValue()).isEqualTo(45.5);
        assertThat(pharmacie.getAssignedTo().getUsername()).isEqualTo("alice");
        assertThat(pharmacie.getPhone()).isEqualTo("(514) 555-0190");
        assertThat(businessRepository.findAll().stream().filter(b -> b.getBusinessName().equals("Chez Léa")).findFirst().orElseThrow().getBusinessType())
                .isEqualTo("Restaurant");

        // importing the same candidates again adds nothing
        JsonNode again = json.readTree(mvc.perform(post("/api/discover/osm/import").with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(again.get("added").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(11);
        assertThat(businessRepository.count()).isEqualTo(11);

        mvc.perform(post("/api/discover/osm/import").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerIsForbidden() throws Exception {
        mvc.perform(post("/api/discover/osm").with(VIEWER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"city\":\"Montréal\",\"categories\":[\"restaurant\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/discover/osm/import").with(VIEWER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidates\":[]}")).andExpect(status().isForbidden());
    }

    private JsonNode search(String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/discover/osm").with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode byId(JsonNode candidates, String osmId) {
        for (JsonNode c : candidates) {
            if (osmId.equals(c.get("osmId").asText())) return c;
        }
        throw new AssertionError("no candidate " + osmId);
    }

    private void lead(String name, String city, String phone) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Restaurant");
        b.setAddressCity(city);
        b.setPhone(phone);
        b.setPhoneNormalized(phone);
        b.setDataSource("Démo");
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
