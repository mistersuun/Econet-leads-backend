package com.econet.leads.integration.places;

import com.econet.leads.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Places API (New) Text Search over plain HTTP. Optional: {@link #isConfigured()} is false
 * while GOOGLE_PLACES_API_KEY is empty. The key is only ever sent in the X-Goog-Api-Key header and is
 * never logged; upstream failures become a neutral 502.
 */
@Component
@Slf4j
public class PlacesClient {

    static final String FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.rating,"
            + "places.userRatingCount,places.regularOpeningHours,places.googleMapsUri,places.websiteUri,"
            + "places.nationalPhoneNumber,places.reviews,places.businessStatus";
    static final String UNAVAILABLE = "Service Google indisponible, réessayez dans un instant";

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public PlacesClient(@Value("${app.places.api-key:}") String apiKey,
                        @Value("${app.places.base-url:https://places.googleapis.com}") String baseUrl,
                        @Value("${app.places.timeout-seconds:15}") long timeoutSeconds,
                        ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** Candidate places for the text query (best first, as Google ranks them); empty when none. */
    public List<JsonNode> searchText(String textQuery) {
        if (!isConfigured()) {
            throw ApiException.serviceUnavailable("Google Places n'est pas configuré (GOOGLE_PLACES_API_KEY)");
        }
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("textQuery", textQuery, "regionCode", "CA", "languageCode", "fr",
                    "pageSize", 5));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/places:searchText"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // the body may echo the request; log only the status and Google's error status
                log.warn("Google Places answered HTTP {} ({})", response.statusCode(), errorStatus(response.body()));
                throw ApiException.badGateway(UNAVAILABLE);
            }
            JsonNode places = mapper.readTree(response.body()).path("places");
            List<JsonNode> result = new ArrayList<>();
            if (places.isArray()) {
                places.forEach(result::add);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badGateway(UNAVAILABLE);
        } catch (IOException e) {
            log.warn("Google Places request failed: {}", e.getClass().getSimpleName());
            throw ApiException.badGateway(UNAVAILABLE);
        }
    }

    private String errorStatus(String body) {
        try {
            return mapper.readTree(body).path("error").path("status").asText("?");
        } catch (IOException | RuntimeException e) {
            return "?";
        }
    }
}
