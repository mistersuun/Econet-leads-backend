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

    private final String overpassUrl;
    private final String nominatimUrl;
    private final Duration timeout;
    private final long minIntervalMs;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private long lastRequestAt;

    public OverpassClient(@Value("${app.discover.overpass-url:https://overpass-api.de/api/interpreter}") String overpassUrl,
                          @Value("${app.discover.nominatim-url:https://nominatim.openstreetmap.org}") String nominatimUrl,
                          @Value("${app.discover.timeout-seconds:25}") long timeoutSeconds,
                          @Value("${app.discover.min-interval-ms:1000}") long minIntervalMs,
                          ObjectMapper mapper) {
        this.overpassUrl = overpassUrl;
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

    /** Elements (nodes and ways with center + tags) for the query. */
    public List<JsonNode> run(String overpassQuery) {
        String body = "data=" + URLEncoder.encode(overpassQuery, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(overpassUrl))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        JsonNode json = send(request, "Overpass");
        JsonNode elements = json.path("elements");
        if (!elements.isArray()) {
            log.warn("Overpass answered without an 'elements' array");
            throw ApiException.badGateway(UNAVAILABLE);
        }
        List<JsonNode> result = new java.util.ArrayList<>();
        elements.forEach(result::add);
        return result;
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
