package com.econet.leads.integration;

import com.econet.leads.model.Business;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for importing CHSLD (Centre d'hébergement de soins de longue durée) data from Données Québec
 * Dataset: Fichier cartographique des établissements du réseau de la santé et des services sociaux
 * Resource ID: a1988030-1f8b-4c67-bc29-ca8b9f710afd
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DonneesQuebecChsldService {

    private static final String CKAN_BASE_URL = "https://www.donneesquebec.ca/recherche/api/3/action/";
    private static final String CHSLD_RESOURCE_ID = "a1988030-1f8b-4c67-bc29-ca8b9f710afd";
    public static final String DATA_SOURCE_NAME = "Données Québec - CHSLD";

    private final CkanApiClient ckanApiClient;

    /**
     * Fetch all establishments from Données Québec, keep only CHSLD ones and map them to Business candidates.
     */
    public List<Business> fetchBusinesses() {
        List<Map<String, Object>> allRecords = ckanApiClient.fetchAllRecords(CKAN_BASE_URL, CHSLD_RESOURCE_ID, 100);
        log.info("Fetched {} establishment records, filtering for CHSLD...", allRecords.size());
        return allRecords.stream()
                .filter(record -> "Oui".equalsIgnoreCase(getString(record, "CHSLD")))
                .map(this::mapChsldRecordToBusiness)
                .collect(Collectors.toList());
    }

    /**
     * Map CKAN CHSLD record to Business entity
     */
    private Business mapChsldRecordToBusiness(Map<String, Object> record) {
        Business business = new Business();

        // Name - try multiple field names and use a fallback
        String installationName = getString(record, "NOM_INSTALLATION_PLUS");
        if (installationName == null || installationName.isEmpty()) {
            installationName = getString(record, "NOM_INSTALLATION");
        }
        if (installationName == null || installationName.isEmpty()) {
            installationName = getString(record, "NOM");
        }
        if (installationName == null || installationName.isEmpty()) {
            installationName = "CHSLD - " + getString(record, "VILLE");
        }
        if (installationName == null || installationName.isEmpty()) {
            installationName = "CHSLD Inconnu";
        }
        business.setBusinessName(installationName);
        business.setBusinessType("CHSLD");

        // Address
        business.setAddressStreet(getString(record, "ADRESSE"));
        business.setAddressCity(getString(record, "VILLE"));
        business.setAddressProvince("QC");
        business.setPostalCode(getString(record, "CODE_POSTAL"));

        // Contact information (if available in dataset)
        business.setPhone(getString(record, "TELEPHONE"));

        // Geocoding (if available)
        String lat = getString(record, "LATITUDE");
        String lon = getString(record, "LONGITUDE");
        if (lat != null && lon != null) {
            try {
                business.setLatitude(new BigDecimal(lat));
                business.setLongitude(new BigDecimal(lon));
            } catch (Exception e) {
                log.debug("Invalid coordinates for CHSLD: {}", business.getBusinessName());
            }
        }

        // Additional fields that might be useful
        // REGION_ADMINISTRATIVE, REGION_SOCIO_SANITAIRE, etc.

        // Metadata
        business.setDataSource(DATA_SOURCE_NAME);
        business.setSourceUrl("https://www.donneesquebec.ca/recherche/dataset/51998b55-7d4c-4381-8c20-0ac1cd9c1b87");
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
