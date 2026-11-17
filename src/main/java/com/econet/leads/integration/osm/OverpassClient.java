package com.econet.leads.integration.osm;

import com.econet.leads.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Minimal Overpass + Nominatim client. Public endpoints with a usage policy: identifying
 * User-Agent, at most one request per second (a shared throttle), 25 s timeout. Any network or
 * upstream failure becomes a 502 {error} with a neutral message; details are only logged.
 */
@Component
@Slf4j
public class OverpassClient {

    public static final String USER_AGENT = "EconetLeads/1.0";
    static final String UNAVAILABLE = "Service OpenStreetMap indisponible, réessayez dans un instant";

    /** south, west, north, east */
    public record BoundingBox(double south, double west, double north, double east) {
    }

    private final List<String> overpassUrls;
    private final Duration rememberFor;
    private final String nominatimUrl;
    private final Duration timeout;
    private final long minIntervalMs;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private long lastRequestAt;
    /** Last endpoint that answered, kept for {@code rememberFor} so a dead mirror is not retried first. */
    private volatile String lastWorking;
    private volatile long lastWorkingAt;

    public OverpassClient(@Value("${app.discover.overpass-url:}") String overpassUrl,
                          @Value("${app.discover.overpass-urls:}") String overpassUrls,
                          @Value("${app.discover.remember-minutes:60}") long rememberMinutes,
                          @Value("${app.discover.nominatim-url:https://nominatim.openstreetmap.org}") String nominatimUrl,
                          @Value("${app.discover.timeout-seconds:25}") long timeoutSeconds,
                          @Value("${app.discover.min-interval-ms:1000}") long minIntervalMs,
                          ObjectMapper mapper) {
        // OVERPASS_URL first when set, then the default list, without duplicates
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (overpassUrl != null && !overpassUrl.isBlank()) {
            urls.add(overpassUrl.trim());
        }
        if (overpassUrls != null) {
            for (String u : overpassUrls.split(",")) {
                if (!u.isBlank()) {
                    urls.add(u.trim());
                }
            }
        }
        this.overpassUrls = List.copyOf(urls);
        this.rememberFor = Duration.ofMinutes(rememberMinutes);
        this.nominatimUrl = nominatimUrl.endsWith("/") ? nominatimUrl.substring(0, nominatimUrl.length() - 1) : nominatimUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.minIntervalMs = minIntervalMs;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Endpoints in the order they will be tried: the remembered working one first (for 1 h), then the configured order. */
    public List<String> endpointOrder() {
        String remembered = lastWorking;
        if (remembered != null && overpassUrls.contains(remembered)
                && System.currentTimeMillis() - lastWorkingAt < rememberFor.toMillis()) {
            List<String> ordered = new java.util.ArrayList<>(overpassUrls);
            ordered.remove(remembered);
            ordered.add(0, remembered);
            return ordered;
        }
        return overpassUrls;
    }

    /**
     * Elements (nodes and ways with center + tags) for the query. Each endpoint is tried in order on
     * connect error, timeout, HTTP 429/5xx or an unusable answer; 502 only when all of them fail.
     */
    public List<JsonNode> run(String overpassQuery) {
        String body = "data=" + URLEncoder.encode(overpassQuery, StandardCharsets.UTF_8);
        if (overpassUrls.isEmpty()) {
            log.warn("No Overpass endpoint configured");
            throw ApiException.badGateway(UNAVAILABLE);
        }
        for (String endpoint : endpointOrder()) {
            List<JsonNode> elements = tryEndpoint(endpoint, body);
            if (elements != null) {
                if (!endpoint.equals(lastWorking)) {
                    log.info("Overpass answered from {}", endpoint);
                }
                lastWorking = endpoint;
                lastWorkingAt = System.currentTimeMillis();
                return elements;
            }
        }
        log.warn("All {} Overpass endpoints failed", overpassUrls.size());
        throw ApiException.badGateway(UNAVAILABLE);
    }

    /** Elements, or null when this endpoint should be skipped in favour of the next one. */
    private List<JsonNode> tryEndpoint(String endpoint, String body) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Skipping invalid Overpass URL {}", endpoint);
            return null;
        }
        throttle();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code == 400) {
                // Overpass's answer to a bad query: another mirror would refuse it too
                log.warn("Overpass {} answered HTTP 400 (bad query)", endpoint);
                throw ApiException.badGateway(UNAVAILABLE);
            }
            if (code / 100 != 2) {
                // 429, 5xx, but also 403/404/...: a blocked or moved mirror says nothing about the query
                log.warn("Overpass {} answered HTTP {}, trying the next endpoint", endpoint, code);
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            String remark = root.path("remark").asText("");
            if (remark.contains("runtime error") || remark.contains("Query timed out") || remark.contains("out of memory")) {
                // an overloaded mirror answers 200 with an empty or partial list and a remark
                log.warn("Overpass {} reported a runtime problem, trying the next endpoint", endpoint);
                return null;
            }
            JsonNode elements = root.path("elements");
            if (!elements.isArray()) {
                log.warn("Overpass {} answered without an 'elements' array, trying the next endpoint", endpoint);
                return null;
            }
            List<JsonNode> result = new java.util.ArrayList<>();
            elements.forEach(result::add);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway(UNAVAILABLE);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            log.warn("Overpass {} failed ({}), trying the next endpoint", endpoint, e.getClass().getSimpleName());
            return null;
        }
    }

    /** Bounding box of the best Nominatim match for the place, if any. */
    public Optional<BoundingBox> geocode(String place) {
        URI uri = URI.create(nominatimUrl + "/search?format=json&limit=1&countrycodes=ca&q="
                + URLEncoder.encode(place, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET().build();
        JsonNode json = send(request, "Nominatim");
        if (!json.isArray() || json.isEmpty()) {
            return Optional.empty();
        }
        JsonNode box = json.get(0).path("boundingbox"); // [south, north, west, east] as strings
        if (!box.isArray() || box.size() != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BoundingBox(box.get(0).asDouble(), box.get(2).asDouble(), box.get(1).asDouble(), box.get(3).asDouble()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private JsonNode send(HttpRequest request, String service) {
        throttle();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("{} answered HTTP {}", service, response.statusCode());
                throw ApiException.badGateway(UNAVAILABLE);
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway(UNAVAILABLE);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            log.warn("{} request failed: {}", service, e.toString());
            throw ApiException.badGateway(UNAVAILABLE);
        }
    }

    /** One request per {@code minIntervalMs} for the whole application. */
    private synchronized void throttle() {
        long wait = lastRequestAt + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }
}
