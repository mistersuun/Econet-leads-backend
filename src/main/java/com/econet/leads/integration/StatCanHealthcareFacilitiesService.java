package com.econet.leads.integration;

import com.econet.leads.model.Business;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for importing Healthcare Facilities from Statistics Canada
 * Note: This is a placeholder implementation - actual CSV URL needs to be configured
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatCanHealthcareFacilitiesService {

    public static final String DATA_SOURCE_NAME = "Statistics Canada - Healthcare Facilities";

    /**
     * Fetch healthcare facilities (mock data for now) and map them to Business candidates.
     */
    public List<Business> fetchBusinesses() {
        return fetchMockHealthcareFacilities().stream()
                .map(this::mapHealthcareFacilityToBusiness)
                .collect(Collectors.toList());
    }

    /**
     * Mock data fetcher - replace with actual CSV download/parse logic
     */
    private List<Map<String, Object>> fetchMockHealthcareFacilities() {
        List<Map<String, Object>> records = new ArrayList<>();

        // Create sample healthcare facilities
        String[] facilities = {
            "Clinique Médicale du Plateau", "Clinique Médicale Notre-Dame",
            "Centre Médical de Laval", "Clinique Santé Plus", "Clinique Médicale du Quartier"
        };

        String[] streets = {
            "1234 Avenue du Mont-Royal Est", "5678 Rue Saint-Denis",
            "9012 Boulevard des Laurentides", "3456 Rue Sherbrooke Ouest", "7890 Avenue Papineau"
        };

        String[] cities = {
            "Montréal", "Québec", "Laval", "Gatineau", "Longueuil"
        };

        for (int i = 0; i < facilities.length; i++) {
            Map<String, Object> record = new HashMap<>();
            record.put("facility_name", facilities[i]);
            record.put("address", streets[i]);
            record.put("city", cities[i]);
            record.put("province", "QC");
            record.put("postal_code", "H2J 1V" + i);
            record.put("phone", "514-555-" + String.format("%04d", 1000 + i));
            record.put("facility_type", "Clinique");
            record.put("_id", "statcan_" + (i + 1));
            records.add(record);
        }

        return records;
    }

    /**
     * Map Healthcare Facility record to Business entity
     */
    private Business mapHealthcareFacilityToBusiness(Map<String, Object> record) {
        Business business = new Business();

        // Name
        business.setBusinessName(getString(record, "facility_name"));

        String facilityType = getString(record, "facility_type");
        business.setBusinessType(facilityType != null ? facilityType : "Clinique");

        // Address
        business.setAddressStreet(getString(record, "address"));
        business.setAddressCity(getString(record, "city"));
        business.setAddressProvince(getString(record, "province"));
        business.setPostalCode(getString(record, "postal_code"));

        // Contact
        business.setPhone(getString(record, "phone"));
        business.setEmail(getString(record, "email"));

        // Metadata
        business.setDataSource(DATA_SOURCE_NAME);
        business.setSourceUrl("https://www150.statcan.gc.ca/n1/en/catalogue/82-006-X");
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
