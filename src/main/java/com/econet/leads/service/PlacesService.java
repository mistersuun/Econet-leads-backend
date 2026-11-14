package com.econet.leads.service;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.exception.ApiException;
import com.econet.leads.integration.ImportJobTracker;
import com.econet.leads.integration.ImportService;
import com.econet.leads.integration.places.PlacesClient;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.DataSourceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Google Places enrichment of leads. The stored JSON (businesses.places) is
 * {placeId, rating, reviewCount, mapsUrl, website, phone, openingHours[], businessStatus,
 * cleanlinessSignals[{text, rating, publishedAt}], fetchedAt}. Google's caching terms allow keeping
 * the data for 30 days: after that only placeId and fetchedAt are kept (expired = true), both when
 * the lead is read ({@link #exposed}) and by the daily purge.
 */
@Service
@Slf4j
public class PlacesService {

    public static final String SOURCE_NAME = "Google Places – enrichissement";
    public static final int MAX_BULK = 50;
    public static final int CACHE_DAYS = 30;
    static final int MAX_SIGNALS = 5;
    static final int SIGNAL_TEXT_MAX = 200;
    static final double NAME_THRESHOLD = 0.8;

    /** FR/EN words that make a review a "cleanliness signal" (compared accent-free, whole words, optional plural). */
    static final List<String> KEYWORDS = List.of(
            "sale", "malpropre", "proprete", "poussiere", "poussiereux", "crasseux", "crasse", "toilette", "toilettes",
            "odeur", "puant", "degoutant", "insalubre", "salete", "hygiene", "ordures",
            "dirty", "unclean", "filthy", "dust", "dusty", "smell", "smelly", "stink", "bathroom", "washroom", "restroom",
            "cleanliness", "grimy", "messy");
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "(?<![a-z])(?:" + String.join("|", KEYWORDS) + ")s?(?![a-z])");

    private final PlacesClient client;
    private final PlacesUsageService usage;
    private final BusinessRepository businessRepository;
    private final DataSourceRepository dataSourceRepository;
    private final ImportService importService;
    private final ImportJobTracker jobTracker;
    private final PriorityScoreService priorityScoreService;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PlacesService(PlacesClient client, PlacesUsageService usage, BusinessRepository businessRepository,
                         DataSourceRepository dataSourceRepository, ImportService importService,
                         ImportJobTracker jobTracker, PriorityScoreService priorityScoreService, Clock clock,
                         PlatformTransactionManager txManager) {
        this.client = client;
        this.usage = usage;
        this.businessRepository = businessRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.importService = importService;
        this.jobTracker = jobTracker;
        this.priorityScoreService = priorityScoreService;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    // ------------------------------------------------------------------ single lead

    /** Looks the lead up on Google and stores the data when the match is confident. 404, 503 not configured, 429 daily limit, 502. */
    public BusinessDTO enrich(UUID businessId) {
        Business updated = enrichBusiness(businessId);
        return priorityScoreService.decorate(DtoMapper.toDto(updated));
    }

    private Business enrichBusiness(UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead introuvable : " + businessId));
        requireConfigured();
        if (!usage.tryConsume()) {
            throw limitReached();
        }
        List<JsonNode> candidates = client.searchText(textQuery(business));
        JsonNode match = bestMatch(business, candidates);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        Map<String, Object> places = match == null ? null : toPlaces(match, now);
        return tx.execute(status -> {
            Business fresh = businessRepository.findById(businessId)
                    .orElseThrow(() -> ApiException.notFound("Lead introuvable : " + businessId));
            fresh.setPlacesCheckedAt(now);
            if (places != null) {
                fresh.setPlaces(places);
                fresh.setPlacesCleanliness(PriorityScoreService.hasCleanlinessSignals(places));
            }
            return businessRepository.save(fresh);
        });
    }

    // ------------------------------------------------------------------ bulk (background job)

    /** Starts one job enriching the leads that have no fresh Google data. 503 not configured, 429 limit reached, 409 already running. */
    public ScraperJob startBulk(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw ApiException.badRequest("ids est requis");
        }
        Set<UUID> unique = new LinkedHashSet<>(ids);
        if (unique.size() > MAX_BULK) {
            throw ApiException.badRequest("Au plus " + MAX_BULK + " leads à la fois");
        }
        requireConfigured();
        if (usage.limitReached()) {
            throw limitReached();
        }
        DataSource source = source();
        return importService.startTask(source, jobId -> runBulk(jobId, source, new ArrayList<>(unique)));
    }

    void runBulk(UUID jobId, DataSource source, List<UUID> ids) {
        int processed = 0;
        int enriched = 0;
        int skipped = 0;
        int failed = 0;
        StringBuilder errors = new StringBuilder();
        StringBuilder notes = new StringBuilder();
        try {
            jobTracker.markRunning(jobId);
            for (UUID id : ids) {
                processed++;
                try {
                    Business b = businessRepository.findById(id).orElse(null);
                    if (b == null) {
                        failed++;
                        errors.append("Lead introuvable : ").append(id).append('\n');
                    } else if (isFresh(b.getPlaces())) {
                        skipped++;
                    } else {
                        Business after = enrichBusiness(id);
                        if (after.getPlaces() != null && isFresh(after.getPlaces())) {
                            enriched++;
                        } else {
                            skipped++;
                        }
                    }
                } catch (ApiException e) {
                    if (e.getStatus().value() == 429) {
                        notes.append("Limite quotidienne atteinte : ").append(ids.size() - processed + 1)
                                .append(" lead(s) non traités\n");
                        break;
                    }
                    failed++;
                    errors.append(id).append(" : ").append(e.getMessage()).append('\n');
                } catch (RuntimeException e) {
                    failed++;
                    errors.append(id).append(" : ").append(e.getClass().getSimpleName()).append('\n');
                    log.warn("Places enrichment of {} failed: {}", id, e.toString());
                }
                if (!jobTracker.updateProgress(jobId, processed, 0, enriched)) {
                    return; // cancelled
                }
            }
            notes.insert(0, String.format("%d traités · %d enrichis · %d ignorés (sans correspondance ou déjà à jour) · %d erreurs%n",
                    processed, enriched, skipped, failed));
            jobTracker.complete(jobId, processed, 0, enriched, errors.toString(), notes.toString());
            jobTracker.updateDataSourceSync(source.getId(), enriched);
        } catch (RuntimeException e) {
            log.error("Places job {} failed: {}", jobId, e.toString());
            jobTracker.fail(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private DataSource source() {
        return dataSourceRepository.findBySourceName(SOURCE_NAME).orElseGet(() -> {
            DataSource ds = new DataSource();
            ds.setSourceName(SOURCE_NAME);
            ds.setSourceType(DataSource.SourceType.MANUAL);
            ds.setSyncFrequency(DataSource.SyncFrequency.MANUAL);
            ds.setActive(false); // never part of a scheduled "import all"
            ds.setConfig(Map.of("importer", "PLACES_ENRICH"));
            return dataSourceRepository.save(ds);
        });
    }

    // ------------------------------------------------------------------ expiry

    /** Strips expired Google data down to placeId + fetchedAt (daily, 06:00). Returns the number of leads purged. */
    public int purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(CACHE_DAYS);
        int purged = 0;
        for (Business candidate : businessRepository.findByPlacesIsNotNullAndPlacesCheckedAtBefore(cutoff)) {
            Map<String, Object> places = candidate.getPlaces();
            if (places == null || (places.size() <= 3 && Boolean.TRUE.equals(places.get("expired")))) {
                continue;
            }
            Map<String, Object> kept = expiredView(places);
            tx.executeWithoutResult(s -> businessRepository.findById(candidate.getId()).ifPresent(b -> {
                b.setPlaces(kept);
                b.setPlacesCleanliness(false);
                businessRepository.save(b);
            }));
            purged++;
        }
        if (purged > 0) {
            log.info("Purged expired Google Places data of {} lead(s)", purged);
        }
        return purged;
    }

    // ------------------------------------------------------------------ pure helpers

    /** What the API exposes: the data while fresh, only {placeId, fetchedAt, expired: true} after 30 days. */
    public static Map<String, Object> exposed(Map<String, Object> places) {
        if (places == null || places.isEmpty()) {
            return null;
        }
        return isFresh(places) ? new LinkedHashMap<>(places) : expiredView(places);
    }

    static Map<String, Object> expiredView(Map<String, Object> places) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("placeId", places.get("placeId"));
        view.put("fetchedAt", places.get("fetchedAt"));
        view.put("expired", true);
        return view;
    }

    /** Fresh = fetched less than 30 days ago and not already stripped. */
    public static boolean isFresh(Map<String, Object> places) {
        if (places == null || Boolean.TRUE.equals(places.get("expired")) || places.get("fetchedAt") == null) {
            return false;
        }
        try {
            return LocalDateTime.parse(places.get("fetchedAt").toString()).isAfter(LocalDateTime.now().minusDays(CACHE_DAYS));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String textQuery(Business b) {
        StringBuilder q = new StringBuilder(b.getBusinessName() == null ? "" : b.getBusinessName().strip());
        if (b.getAddressStreet() != null && !b.getAddressStreet().isBlank()) {
            q.append(' ').append(b.getAddressStreet().strip());
        }
        if (b.getAddressCity() != null && !b.getAddressCity().isBlank()) {
            q.append(' ').append(b.getAddressCity().strip());
        }
        return q.toString();
    }

    /** The candidate with the same phone, else the most similar name (>= 0.8); null when nothing is confident. */
    static JsonNode bestMatch(Business b, List<JsonNode> candidates) {
        String phone = com.econet.leads.util.PhoneFormatter.normalize(b.getPhone());
        String name = com.econet.leads.util.StringSimilarity.normalize(b.getBusinessName());
        JsonNode best = null;
        double bestScore = 0;
        for (JsonNode candidate : candidates) {
            double score = 0;
            String candidatePhone = com.econet.leads.util.PhoneFormatter.normalize(candidate.path("nationalPhoneNumber").asText(null));
            if (phone != null && phone.length() >= 10 && phone.equals(candidatePhone)) {
                score = 2.0;
            } else {
                String other = com.econet.leads.util.StringSimilarity.normalize(candidate.path("displayName").path("text").asText(""));
                score = com.econet.leads.util.StringSimilarity.calculate(name, other);
                if (score < NAME_THRESHOLD && name.length() >= 6 && other.length() >= 6
                        && (other.contains(name) || name.contains(other))) {
                    score = NAME_THRESHOLD; // "Bistro Le Comptoir" vs "Bistro Le Comptoir Plateau"
                }
                if (score < NAME_THRESHOLD) {
                    score = 0;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    static Map<String, Object> toPlaces(JsonNode place, LocalDateTime fetchedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("placeId", text(place, "id"));
        m.put("rating", place.hasNonNull("rating") ? place.get("rating").asDouble() : null);
        m.put("reviewCount", place.hasNonNull("userRatingCount") ? place.get("userRatingCount").asInt() : null);
        m.put("mapsUrl", text(place, "googleMapsUri"));
        m.put("website", text(place, "websiteUri"));
        m.put("phone", text(place, "nationalPhoneNumber"));
        List<String> hours = new ArrayList<>();
        place.path("regularOpeningHours").path("weekdayDescriptions").forEach(n -> hours.add(n.asText()));
        m.put("openingHours", hours);
        m.put("businessStatus", text(place, "businessStatus"));
        m.put("cleanlinessSignals", cleanlinessSignals(place.path("reviews")));
        m.put("fetchedAt", fetchedAt.toString());
        return m;
    }

    static List<Map<String, Object>> cleanlinessSignals(JsonNode reviews) {
        List<Map<String, Object>> signals = new ArrayList<>();
        if (!reviews.isArray()) {
            return signals;
        }
        for (JsonNode review : reviews) {
            String text = review.path("text").path("text").asText(review.path("originalText").path("text").asText(""));
            if (text.isBlank() || !mentionsCleanliness(text)) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            String clean = text.strip().replaceAll("\\s+", " ");
            s.put("text", clean.length() > SIGNAL_TEXT_MAX ? clean.substring(0, SIGNAL_TEXT_MAX - 1) + "…" : clean);
            s.put("rating", review.hasNonNull("rating") ? review.get("rating").asInt() : null);
            s.put("publishedAt", text(review, "publishTime"));
            signals.add(s);
            if (signals.size() >= MAX_SIGNALS) {
                break;
            }
        }
        return signals;
    }

    public static boolean mentionsCleanliness(String text) {
        String plain = java.text.Normalizer.normalize(text.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", " "); // "L'odeur" -> "l odeur"
        return KEYWORD_PATTERN.matcher(plain).find();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private void requireConfigured() {
        if (!client.isConfigured()) {
            throw ApiException.serviceUnavailable("Google Places n'est pas configuré (GOOGLE_PLACES_API_KEY)");
        }
    }

    private ApiException limitReached() {
        return ApiException.tooManyRequests("Limite quotidienne Google Places atteinte (" + usage.dailyLimit()
                + " appels par jour, PLACES_DAILY_LIMIT)");
    }
}
