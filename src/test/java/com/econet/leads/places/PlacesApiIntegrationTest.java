package com.econet.leads.places;

import com.econet.leads.model.Business;
import com.econet.leads.model.PlacesUsage;
import com.econet.leads.model.SavedSearch;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.PlacesUsageRepository;
import com.econet.leads.repository.SavedSearchRepository;
import com.econet.leads.repository.ScraperJobRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.service.NotificationService;
import com.econet.leads.service.PlacesService;
import com.econet.leads.service.SavedSearchService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Google Places enrichment and saved searches against local stand-ins for Google and Overpass. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlacesApiIntegrationTest {

    static final HttpServer SERVER;
    static final List<String> GOOGLE_BODIES = new CopyOnWriteArrayList<>();
    static final List<String> GOOGLE_KEYS = new CopyOnWriteArrayList<>();
    static final List<String> GOOGLE_MASKS = new CopyOnWriteArrayList<>();
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String BISTRO = """
            {"places":[{"id":"ChIJbistro","displayName":{"text":"Bistro Le Comptoir"},"formattedAddress":"250 Av. du Mont-Royal E, Montréal",
             "rating":4.3,"userRatingCount":187,"googleMapsUri":"https://maps.google.com/?cid=1","websiteUri":"https://lecomptoir.example",
             "nationalPhoneNumber":"(514) 555-0161","businessStatus":"OPERATIONAL",
             "regularOpeningHours":{"weekdayDescriptions":["lundi: 11 h 00 – 22 h 00","mardi: 11 h 00 – 22 h 00"]},
             "reviews":[
              {"rating":2,"publishTime":"2026-09-01T12:00:00Z","text":{"text":"Les toilettes étaient sales et ça sentait mauvais. Service correct."}},
              {"rating":5,"publishTime":"2026-09-02T12:00:00Z","text":{"text":"Excellent repas, merci!"}},
              {"rating":1,"publishTime":"2026-08-02T12:00:00Z","text":{"text":"Dirty tables and dust everywhere"}}]}]}""";
    static final String OTHER = """
            {"places":[{"id":"ChIJother","displayName":{"text":"Quincaillerie Tremblay"},"rating":4.0,"userRatingCount":10,
             "nationalPhoneNumber":"(450) 555-9999"}]}""";

    static {
        try {
            String fixture = Files.readString(Path.of("src/test/resources/fixtures/overpass-montreal.json"));
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            SERVER.createContext("/v1/places:searchText", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                GOOGLE_BODIES.add(body);
                GOOGLE_KEYS.add(ex.getRequestHeaders().getFirst("X-Goog-Api-Key"));
                GOOGLE_MASKS.add(ex.getRequestHeaders().getFirst("X-Goog-FieldMask"));
                int status = 200;
                String answer;
                if (body.contains("Boom")) {
                    status = 500;
                    answer = "{\"error\":{\"status\":\"INTERNAL\",\"message\":\"secret-internal-detail\"}}";
                } else if (body.contains("Bistro Le Comptoir")) {
                    answer = BISTRO;
                } else if (body.contains("Mystère")) {
                    answer = OTHER;
                } else {
                    answer = "{}";
                }
                reply(ex, status, answer);
            });
            SERVER.createContext("/api/interpreter", ex -> {
                ex.getRequestBody().readAllBytes();
                reply(ex, 200, fixture);
            });
            SERVER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        r.add("app.places.base-url", () -> base);
        r.add("app.places.api-key", () -> "test-key-123");
        r.add("app.places.daily-limit", () -> "6");
        r.add("app.discover.overpass-url", () -> base + "/api/interpreter");
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
    @Autowired PlacesUsageRepository usageRepository;
    @Autowired SavedSearchRepository savedSearchRepository;
    @Autowired ScraperJobRepository jobRepository;
    @Autowired PlacesService placesService;
    @Autowired SavedSearchService savedSearchService;
    @Autowired NotificationService notificationService;
    @Autowired java.time.Clock clock;

    static final RequestPostProcessor ALICE = user("alice").roles("USER");
    static final RequestPostProcessor ADMIN = user("boss").roles("ADMIN");
    static final RequestPostProcessor VIEWER = user("viewer").roles("VIEWER");

    User alice;

    @BeforeEach
    void setUp() {
        savedSearchRepository.deleteAll();
        contactRepository.deleteAll();
        businessRepository.deleteAll();
        usageRepository.deleteAll();
        alice = ensureUser("alice", User.UserRole.USER);
        ensureUser("boss", User.UserRole.ADMIN);
        ensureUser("viewer", User.UserRole.VIEWER);
        GOOGLE_BODIES.clear();
        GOOGLE_KEYS.clear();
        GOOGLE_MASKS.clear();
    }

    // ------------------------------------------------------------------ enrichment

    @Test
    void enrichStoresAConfidentMatchWithCleanlinessQuotes() throws Exception {
        Business b = lead("Bistro Le Comptoir", "Montréal", "5145550161");

        JsonNode dto = enrich(b.getId());
        JsonNode places = dto.get("places");
        assertThat(places.get("placeId").asText()).isEqualTo("ChIJbistro");
        assertThat(places.get("rating").asDouble()).isEqualTo(4.3);
        assertThat(places.get("reviewCount").asInt()).isEqualTo(187);
        assertThat(places.get("mapsUrl").asText()).isEqualTo("https://maps.google.com/?cid=1");
        assertThat(places.get("website").asText()).isEqualTo("https://lecomptoir.example");
        assertThat(places.get("businessStatus").asText()).isEqualTo("OPERATIONAL");
        assertThat(places.get("openingHours")).hasSize(2);
        assertThat(places.get("cleanlinessSignals")).hasSize(2);
        assertThat(places.get("cleanlinessSignals").get(0).get("text").asText()).contains("toilettes");
        assertThat(places.get("cleanlinessSignals").get(0).get("rating").asInt()).isEqualTo(2);
        assertThat(places.get("fetchedAt").asText()).isNotBlank();
        // +10 on the priority when there are cleanliness quotes
        Business reloaded = businessRepository.findById(b.getId()).orElseThrow();
        assertThat(reloaded.isPlacesCleanliness()).isTrue();
        assertThat(reloaded.getPlacesCheckedAt()).isNotNull();
        double base = (0.5 * (reloaded.getDataQualityScore() == null ? 0 : reloaded.getDataQualityScore())) + 50 * 0.2;
        assertThat(dto.get("priorityScore").asDouble()).isEqualTo(Math.round((base + 10) * 10) / 10.0);

        // the request: key and field mask in headers (never in the body), Canada/French, name + city query
        assertThat(GOOGLE_KEYS).containsExactly("test-key-123");
        assertThat(GOOGLE_MASKS.get(0)).contains("places.reviews").contains("places.regularOpeningHours");
        JsonNode body = MAPPER.readTree(GOOGLE_BODIES.get(0));
        assertThat(body.get("textQuery").asText()).isEqualTo("Bistro Le Comptoir Montréal");
        assertThat(body.get("regionCode").asText()).isEqualTo("CA");
        assertThat(body.get("languageCode").asText()).isEqualTo("fr");
        assertThat(GOOGLE_BODIES.get(0)).doesNotContain("test-key-123");

        // GET shows it too, and the call was counted
        mvc.perform(get("/api/businesses/" + b.getId()).with(VIEWER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.places.placeId").value("ChIJbistro"));
        mvc.perform(get("/api/places/status").with(ALICE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.usedToday").value(1))
                .andExpect(jsonPath("$.dailyLimit").value(6)).andExpect(jsonPath("$.attribution").value("Données Google"));
    }

    @Test
    void anUnconfidentMatchIsNotStoredButTheLookupIsRemembered() throws Exception {
        Business b = lead("Mystère Nettoyage", "Laval", "4505550000");
        JsonNode dto = enrich(b.getId());
        assertThat(dto.get("places").isNull()).isTrue();
        Business reloaded = businessRepository.findById(b.getId()).orElseThrow();
        assertThat(reloaded.getPlaces()).isNull();
        assertThat(reloaded.getPlacesCheckedAt()).isNotNull();
    }

    @Test
    void aMatchOnThePhoneNumberIsConfidentEvenWithADifferentName() throws Exception {
        Business b = lead("Le Comptoir (ancien nom) Bistro", "Montréal", "514-555-0161");
        // query contains the old name only: the fake answers for names containing "Bistro Le Comptoir"
        b.setBusinessName("Bistro Le Comptoir enr.");
        businessRepository.save(b);
        assertThat(enrich(b.getId()).get("places").get("placeId").asText()).isEqualTo("ChIJbistro");
    }

    @Test
    void googleFailureIs502WithoutLeakingDetailsOrTheKey() throws Exception {
        Business b = lead("Boom Entretien", "Montréal", "5145550199");
        mvc.perform(post("/api/businesses/" + b.getId() + "/enrich/places").with(ALICE))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("secret-internal-detail"))))
                .andExpect(content().string(not(containsString("test-key-123"))));
        assertThat(businessRepository.findById(b.getId()).orElseThrow().getPlaces()).isNull();
    }

    @Test
    void permissionsAndUnknownLead() throws Exception {
        Business b = lead("Bistro Le Comptoir", "Montréal", "5145550161");
        mvc.perform(post("/api/businesses/" + b.getId() + "/enrich/places").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/businesses/" + b.getId() + "/enrich/places")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/businesses/enrich/places").with(VIEWER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + b.getId() + "\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/businesses/" + UUID.randomUUID() + "/enrich/places").with(ALICE)).andExpect(status().isNotFound());
        assertThat(GOOGLE_BODIES).isEmpty();
    }

    @Test
    void dailyLimitIs429ForSingleAndBulk() throws Exception {
        Business b = lead("Bistro Le Comptoir", "Montréal", "5145550161");
        usageRepository.save(new PlacesUsage(LocalDate.now(clock), 6));
        // the integrations status (Réglages > Intégrations) reports the same counter
        mvc.perform(get("/api/integrations/status").with(ALICE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.places").value(true))
                .andExpect(jsonPath("$.placesUsedToday").value(6))
                .andExpect(jsonPath("$.placesDailyLimit").value(6));
        mvc.perform(post("/api/businesses/" + b.getId() + "/enrich/places").with(ALICE))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error", containsString("PLACES_DAILY_LIMIT")));
        mvc.perform(post("/api/businesses/enrich/places").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + b.getId() + "\"]}")).andExpect(status().isTooManyRequests());
        assertThat(GOOGLE_BODIES).isEmpty();
    }

    @Test
    void bulkEnrichRunsAsAJobAndSkipsFreshLeads() throws Exception {
        Business a = lead("Bistro Le Comptoir", "Montréal", "5145550161");
        Business fresh = lead("Bistro Le Comptoir Deux", "Montréal", "5145550162");
        Map<String, Object> places = new LinkedHashMap<>();
        places.put("placeId", "ChIJfresh");
        places.put("cleanlinessSignals", List.of());
        places.put("fetchedAt", LocalDateTime.now().withNano(0).toString());
        fresh.setPlaces(places);
        businessRepository.save(fresh);

        String body = "{\"ids\":[\"" + a.getId() + "\",\"" + fresh.getId() + "\"]}";
        JsonNode job = json.readTree(mvc.perform(post("/api/businesses/enrich/places").with(ALICE)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        ScraperJob done = awaitJob(UUID.fromString(job.get("id").asText()));
        assertThat(done.getStatus()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
        assertThat(done.getRecordsProcessed()).isEqualTo(2);
        assertThat(done.getRecordsUpdated()).isEqualTo(1);
        assertThat(GOOGLE_BODIES).hasSize(1); // the fresh lead cost nothing
        assertThat(businessRepository.findById(a.getId()).orElseThrow().getPlaces().get("placeId")).isEqualTo("ChIJbistro");
        assertThat(businessRepository.findById(fresh.getId()).orElseThrow().getPlaces().get("placeId")).isEqualTo("ChIJfresh");
    }

    @Test
    void bulkValidation() throws Exception {
        mvc.perform(post("/api/businesses/enrich/places").with(ALICE).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());
        StringBuilder ids = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < 51; i++) {
            ids.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
        }
        mvc.perform(post("/api/businesses/enrich/places").with(ALICE).contentType(MediaType.APPLICATION_JSON).content(ids + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void googleDataExpiresAfter30DaysButThePlaceIdIsKept() throws Exception {
        Business b = lead("Bistro Le Comptoir", "Montréal", "5145550161");
        Map<String, Object> places = new LinkedHashMap<>();
        places.put("placeId", "ChIJold");
        places.put("rating", 4.1);
        places.put("cleanlinessSignals", List.of(Map.of("text", "sale", "rating", 1)));
        places.put("fetchedAt", LocalDateTime.now().minusDays(45).withNano(0).toString());
        b.setPlaces(places);
        b.setPlacesCheckedAt(LocalDateTime.now().minusDays(45));
        b.setPlacesCleanliness(true);
        businessRepository.save(b);

        mvc.perform(get("/api/businesses/" + b.getId()).with(ALICE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.places.placeId").value("ChIJold"))
                .andExpect(jsonPath("$.places.expired").value(true))
                .andExpect(jsonPath("$.places.rating").doesNotExist())
                .andExpect(jsonPath("$.places.cleanlinessSignals").doesNotExist());

        assertThat(placesService.purgeExpired()).isEqualTo(1);
        Business purged = businessRepository.findById(b.getId()).orElseThrow();
        assertThat(purged.getPlaces()).containsOnlyKeys("placeId", "fetchedAt", "expired");
        assertThat(purged.isPlacesCleanliness()).isFalse();
        assertThat(placesService.purgeExpired()).isZero();
    }

    @Test
    void cleanlinessKeywordsMatchFrenchAndEnglishWholeWords() {
        for (String text : List.of("Toilettes sales", "Un peu de poussière partout", "Très malpropre", "L'odeur était forte",
                "dirty floor", "so much dust", "The bathroom was awful", "bad smell", "Propreté douteuse")) {
            assertThat(PlacesService.mentionsCleanliness(text)).as(text).isTrue();
        }
        for (String text : List.of("Excellent service", "Salle à manger agréable", "Parfait!")) {
            assertThat(PlacesService.mentionsCleanliness(text)).as(text).isFalse();
        }
    }

    // ------------------------------------------------------------------ saved searches

    @Test
    void savedSearchCrudAndPermissions() throws Exception {
        String body = "{\"name\":\"Restos Montréal\",\"city\":\"Montréal\",\"categories\":[\"restaurant\"],\"maxResults\":50,\"frequency\":\"weekly\"}";
        JsonNode created = json.readTree(mvc.perform(post("/api/saved-searches").with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.frequency").value("weekly"))
                .andExpect(jsonPath("$.createdByName").value("alice")).andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String id = created.get("id").asText();

        mvc.perform(get("/api/saved-searches").with(VIEWER)).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Restos Montréal"));
        mvc.perform(post("/api/saved-searches").with(VIEWER).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());

        mvc.perform(put("/api/saved-searches/" + id).with(ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Restos et cafés\",\"city\":\"Laval\",\"categories\":[\"restaurant\",\"retail\"],\"frequency\":\"monthly\",\"active\":false,\"assignToUserId\":\"" + alice.getId() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Restos et cafés"))
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.assignToName").value("alice"))
                .andExpect(jsonPath("$.maxResults").value(50));

        // validation
        mvc.perform(post("/api/saved-searches").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"city\":\"Laval\",\"categories\":[\"restaurant\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/saved-searches").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"city\":\"Laval\",\"categories\":[\"casino\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/saved-searches").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"city\":\"Laval\",\"categories\":[\"restaurant\"],\"frequency\":\"daily\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/saved-searches").with(ALICE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"city\":\"Laval\",\"categories\":[\"restaurant\"],\"assignToUserId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/saved-searches/" + UUID.randomUUID()).with(ALICE).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());

        // delete is admin only
        mvc.perform(delete("/api/saved-searches/" + id).with(ALICE)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/saved-searches/" + id).with(ADMIN)).andExpect(status().isNoContent());
        assertThat(savedSearchRepository.count()).isZero();
    }

    @Test
    void runNowImportsOnlyNewCandidatesAssignsThemAndNotifiesTheAssignee() throws Exception {
        lead("Bistro Le Comptoir", "Montréal", "5145550161"); // already a lead: never re-imported
        SavedSearch s = savedSearch("Restos", 30);
        s.setAssignTo(alice);
        savedSearchRepository.save(s);

        JsonNode job = json.readTree(mvc.perform(post("/api/saved-searches/" + s.getId() + "/run").with(ALICE))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        ScraperJob done = awaitJob(UUID.fromString(job.get("id").asText()));
        assertThat(done.getStatus()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
        assertThat(done.getRecordsAdded()).isEqualTo(10);

        List<Business> added = businessRepository.findAll().stream().filter(b -> "OpenStreetMap".equals(b.getDataSource())).toList();
        assertThat(added).hasSize(10).allMatch(b -> b.getAssignedTo() != null && b.getAssignedTo().getUsername().equals("alice"));
        SavedSearch after = savedSearchRepository.findById(s.getId()).orElseThrow();
        assertThat(after.getLastAdded()).isEqualTo(10);
        assertThat(after.getLastRunAt()).isNotNull();

        var mine = notificationService.forUser(alice);
        assertThat(mine).anyMatch(n -> n.kind().equals("saved_search") && n.title().equals("Recherche « Restos » : 10 nouveaux leads"));
        assertThat(notificationService.forUser(userRepository.findByUsername("boss").orElseThrow()))
                .noneMatch(n -> n.kind().equals("saved_search"));

        // a second run finds nothing new
        assertThat(savedSearchService.runDue()).isZero(); // not due yet
    }

    @Test
    void dueSearchesRunOnTheirScheduleAndPausedOnesDoNot() {
        SavedSearch never = savedSearchRepository.save(savedSearch("Jamais lancée", -1));
        SavedSearch monthlyRecent = savedSearch("Mensuelle récente", 29);
        SavedSearch monthlyDue = savedSearch("Mensuelle due", 30);
        SavedSearch weeklyDue = savedSearch("Hebdo due", 7);
        weeklyDue.setFrequency(SavedSearch.WEEKLY);
        SavedSearch weeklyRecent = savedSearch("Hebdo récente", 6);
        weeklyRecent.setFrequency(SavedSearch.WEEKLY);
        SavedSearch paused = savedSearch("En pause", 90);
        paused.setActive(false);
        savedSearchRepository.saveAll(List.of(monthlyRecent, monthlyDue, weeklyDue, weeklyRecent, paused));

        assertThat(savedSearchService.runDue()).isEqualTo(3); // never, monthlyDue, weeklyDue
        assertThat(savedSearchRepository.findById(never.getId()).orElseThrow().getLastRunAt()).isNotNull();
        assertThat(savedSearchRepository.findById(monthlyRecent.getId()).orElseThrow().getLastRunAt())
                .isBefore(LocalDateTime.now().minusDays(28));
        assertThat(savedSearchRepository.findById(paused.getId()).orElseThrow().getLastRunAt())
                .isBefore(LocalDateTime.now().minusDays(80));
    }

    // ------------------------------------------------------------------ helpers

    private SavedSearch savedSearch(String name, int daysSinceLastRun) {
        SavedSearch s = new SavedSearch();
        s.setName(name);
        s.setCity("Montréal");
        s.setCategories(new java.util.ArrayList<>(List.of("restaurant")));
        s.setMaxResults(50);
        s.setCreatedBy(alice);
        if (daysSinceLastRun >= 0) {
            s.setLastRunAt(LocalDateTime.now().minusDays(daysSinceLastRun).minusMinutes(5));
        }
        return savedSearchRepository.save(s);
    }

    private JsonNode enrich(UUID id) throws Exception {
        return json.readTree(mvc.perform(post("/api/businesses/" + id + "/enrich/places").with(ALICE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ScraperJob awaitJob(UUID id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ScraperJob job = jobRepository.findById(id).orElseThrow();
            if (job.isTerminal()) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    private Business lead(String name, String city, String phone) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Restaurant");
        b.setAddressCity(city);
        b.setPhone(phone);
        b.setPhoneNormalized(phone.replaceAll("\\D", ""));
        b.setDataSource("Manuel");
        return businessRepository.save(b);
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
