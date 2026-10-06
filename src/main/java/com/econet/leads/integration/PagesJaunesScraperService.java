package com.econet.leads.integration;

import com.econet.leads.model.Business;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for scraping business data from Pages Jaunes (Yellow Pages)
 * IMPORTANT: This is a MOCK implementation for demonstration purposes
 * Real implementation requires:
 * - Selenium WebDriver for JavaScript rendering
 * - Rate limiting (3000ms between requests)
 * - Proper User-Agent and headers
 * - Respect for robots.txt
 * - Commercial agreement with Pages Jaunes for data usage
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PagesJaunesScraperService {

    public static final String DATA_SOURCE_NAME = "Pages Jaunes - Manual Scraping";
    private static final int RATE_LIMIT_MS = 3000; // 3 seconds between requests

    @Value("${app.scraper.mock-pages-jaunes:false}")
    private boolean mockEnabled;

    /**
     * Scrape (mock) Pages Jaunes and map the results to Business candidates.
     */
    public List<Business> fetchBusinesses() {
        if (!mockEnabled) {
            throw new IllegalStateException(
                    "Pages Jaunes is a mock source that returns invented businesses; it can only run with app.scraper.mock-pages-jaunes=true (local development).");
        }
        return performMockScraping().stream()
                .map(this::mapScrapedDataToBusiness)
                .collect(Collectors.toList());
    }

    /**
     * Mock scraping function - replace with actual Selenium WebDriver implementation
     */
    private List<Map<String, Object>> performMockScraping() {
        List<Map<String, Object>> scrapedData = new ArrayList<>();

        // Sample businesses across different categories
        Object[][] sampleBusinesses = {
            // Restaurants
            {"Restaurant Le Gourmet", "Restaurant", "2345 Rue Saint-Laurent", "Montréal", "H2X 2T1", "514-555-2001"},
            {"Café Bistro Central", "Restaurant", "6789 Avenue du Parc", "Montréal", "H2V 4E7", "514-555-2002"},
            {"Restaurant La Belle Province", "Restaurant", "4567 Boulevard René-Lévesque", "Québec", "G1R 2B5", "418-555-2003"},

            // CPE / Garderies
            {"Garderie Les Petits Loups", "CPE", "8901 Rue de la Montagne", "Laval", "H7N 5B3", "450-555-3001"},
            {"CPE Les Bambins Joyeux", "CPE", "1234 Avenue des Érables", "Longueuil", "J4H 3W2", "450-555-3002"},

            // CHSLD
            {"Résidence du Parc", "CHSLD", "5678 Chemin de la Côte-des-Neiges", "Montréal", "H3V 1A2", "514-555-4001"},
            {"CHSLD Saint-Joseph", "CHSLD", "9012 Rue Notre-Dame", "Québec", "G1K 8A4", "418-555-4002"},

            // Cliniques
            {"Clinique Médicale Familiale", "Clinique", "3456 Rue Sherbrooke", "Sherbrooke", "J1H 5K4", "819-555-5001"},
            {"Centre Médical du Vieux-Port", "Clinique", "7890 Rue de la Commune", "Montréal", "H2Y 1J1", "514-555-5002"},
            {"Clinique Sans Rendez-vous", "Clinique", "2345 Boulevard Taschereau", "Brossard", "J4W 1M9", "450-555-5003"}
        };

        for (int i = 0; i < sampleBusinesses.length; i++) {
            Object[] data = sampleBusinesses[i];
            Map<String, Object> record = new HashMap<>();
            record.put("business_name", data[0]);
            record.put("business_type", data[1]);
            record.put("address", data[2]);
            record.put("city", data[3]);
            record.put("postal_code", data[4]);
            record.put("phone", data[5]);
            record.put("_id", "pj_" + (i + 1));
            scrapedData.add(record);
        }

        log.info("Mock scraping generated {} sample records", scrapedData.size());
        return scrapedData;
    }

    /**
     * Map scraped data to Business entity
     */
    private Business mapScrapedDataToBusiness(Map<String, Object> record) {
        Business business = new Business();

        // Name
        business.setBusinessName(getString(record, "business_name"));
        business.setBusinessType(getString(record, "business_type"));

        // Address
        business.setAddressStreet(getString(record, "address"));
        business.setAddressCity(getString(record, "city"));
        business.setAddressProvince("QC");
        business.setPostalCode(getString(record, "postal_code"));

        // Contact
        business.setPhone(getString(record, "phone"));
        business.setWebsite(getString(record, "website"));
        business.setEmail(getString(record, "email"));

        // Metadata
        business.setDataSource(DATA_SOURCE_NAME);
        business.setSourceUrl("https://www.pagesjaunes.ca");
        business.setExternalId(getString(record, "_id"));

        return business;
    }

    private String getString(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (value == null) return null;
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }
}
