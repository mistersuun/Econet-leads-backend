package com.econet.leads.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Client for interacting with CKAN API endpoints
 * Used for Données Québec, Données Montréal, and other open data portals
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CkanApiClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Fetch data from a CKAN datastore
     *
     * @param baseUrl CKAN API base URL (e.g., https://www.donneesquebec.ca/recherche/api/3/action/)
     * @param resourceId Resource ID from CKAN dataset
     * @param limit Number of records to fetch
     * @param offset Offset for pagination
     * @return List of records as maps
     */
    public List<Map<String, Object>> fetchDatastoreRecords(
            String baseUrl,
            String resourceId,
            int limit,
            int offset) {

        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "datastore_search")
                    .queryParam("resource_id", resourceId)
                    .queryParam("limit", limit)
                    .queryParam("offset", offset)
                    .toUriString();

            log.info("Fetching CKAN data from: {}", url);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("success") && root.get("success").asBoolean()) {
                JsonNode records = root.path("result").path("records");
                List<Map<String, Object>> result = new ArrayList<>();

                records.forEach(record -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = objectMapper.convertValue(record, Map.class);
                        result.add(map);
                    } catch (Exception e) {
                        log.warn("Failed to convert record: {}", e.getMessage());
                    }
                });

                log.info("Successfully fetched {} records", result.size());
                return result;
            } else {
                log.error("CKAN API returned success=false");
                return List.of();
            }

        } catch (Exception e) {
            log.error("Error fetching CKAN data: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get total record count for a resource
     */
    public int getTotalRecordCount(String baseUrl, String resourceId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "datastore_search")
                    .queryParam("resource_id", resourceId)
                    .queryParam("limit", 1)
                    .toUriString();

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.has("success") && root.get("success").asBoolean()) {
                return root.path("result").path("total").asInt(0);
            }
        } catch (Exception e) {
            log.error("Error getting record count: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Fetch all records with automatic pagination
     */
    public List<Map<String, Object>> fetchAllRecords(String baseUrl, String resourceId, int batchSize) {
        List<Map<String, Object>> allRecords = new ArrayList<>();
        int offset = 0;
        boolean hasMore = true;

        while (hasMore) {
            List<Map<String, Object>> batch = fetchDatastoreRecords(baseUrl, resourceId, batchSize, offset);

            if (batch.isEmpty()) {
                hasMore = false;
            } else {
                allRecords.addAll(batch);
                offset += batchSize;

                log.info("Fetched {} total records so far...", allRecords.size());

                // Sleep to avoid overwhelming the API
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Completed fetching {} total records", allRecords.size());
        return allRecords;
    }
}
