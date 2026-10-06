package com.econet.leads.integration;

import com.econet.leads.model.Business;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for importing Restaurant permits from Données Montréal
 * Dataset: https://donnees.montreal.ca/dataset/permis-restaurants
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DonneesMontrealRestaurantService {

    private static final String CKAN_BASE_URL = "https://donnees.montreal.ca/api/3/action/";
    private static final String RESTAURANT_RESOURCE_ID = "c755bd6f-bc70-46e4-b41d-8ae42d91c67e";
    public static final String DATA_SOURCE_NAME = "Données Montréal - Restaurants";

    private final CkanApiClient ckanApiClient;

    /**
     * Fetch all restaurant records from Données Montréal and map them to Business candidates.
     */
    public List<Business> fetchBusinesses() {
        List<Map<String, Object>> records = ckanApiClient.fetchAllRecords(CKAN_BASE_URL, RESTAURANT_RESOURCE_ID, 100);
        log.info("Fetched {} restaurant records", records.size());
        return records.stream().map(this::mapRestaurantRecordToBusiness).collect(Collectors.toList());
    }

    /**
     * Map Montreal restaurant record to Business entity
     */
    private Business mapRestaurantRecordToBusiness(Map<String, Object> record) {
        Business business = new Business();

        // Name - try different field names
        String name = getString(record, "nom_etablissement");
        if (name == null) name = getString(record, "nom");
        if (name == null) name = getString(record, "name");
        business.setBusinessName(name);
        business.setBusinessType("Restaurant");

        // Address
        String street = getString(record, "adresse");
        if (street == null) street = getString(record, "address");
        business.setAddressStreet(street);

        business.setAddressCity("Montréal");
        business.setAddressProvince("QC");

        String postalCode = getString(record, "code_postal");
        if (postalCode == null) postalCode = getString(record, "postal_code");
        business.setPostalCode(postalCode);

        // Contact
        business.setPhone(getString(record, "telephone"));

        // Metadata
        business.setDataSource(DATA_SOURCE_NAME);
        business.setSourceUrl("https://donnees.montreal.ca/dataset/permis-restaurants");
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
