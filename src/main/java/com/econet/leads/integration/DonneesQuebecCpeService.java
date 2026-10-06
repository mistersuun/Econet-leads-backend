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
 * Service for importing CPE (Centre de la petite enfance) data from Données Québec
 * Dataset: https://www.donneesquebec.ca/recherche/dataset/liste-des-centres-de-la-petite-enfance-cpe-et-des-garderies-en-fonction
 * Resource ID: 89af3537-4506-488c-8d0e-6d85b4033a0e
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DonneesQuebecCpeService {

    private static final String CKAN_BASE_URL = "https://www.donneesquebec.ca/recherche/api/3/action/";
    private static final String CPE_RESOURCE_ID = "89af3537-4506-488c-8d0e-6d85b4033a0e";
    public static final String DATA_SOURCE_NAME = "Données Québec - CPE";

    private final CkanApiClient ckanApiClient;

    /**
     * Fetch all CPE records from Données Québec and map them to Business candidates.
     */
    public List<Business> fetchBusinesses() {
        List<Map<String, Object>> records = ckanApiClient.fetchAllRecords(CKAN_BASE_URL, CPE_RESOURCE_ID, 100);
        log.info("Fetched {} CPE records", records.size());
        return records.stream().map(this::mapCpeRecordToBusiness).collect(Collectors.toList());
    }

    /**
     * Map CKAN CPE record to Business entity
     */
    private Business mapCpeRecordToBusiness(Map<String, Object> record) {
        Business business = new Business();

        // Name
        business.setBusinessName(getString(record, "NOM"));
        business.setBusinessType("CPE");

        // Address
        business.setAddressStreet(getString(record, "ADRESSE"));
        business.setAddressCity(getString(record, "NOM_MUN_COMPO"));
        business.setAddressProvince("QC");
        business.setPostalCode(getString(record, "CODE_POSTAL_COMPO"));

        // Contact
        business.setPhone(getString(record, "telephone1"));
        business.setEmail(getString(record, "INTERNET"));

        // Metadata
        business.setDataSource(DATA_SOURCE_NAME);
        business.setSourceUrl("https://www.donneesquebec.ca/recherche/dataset/liste-des-centres-de-la-petite-enfance-cpe-et-des-garderies-en-fonction");
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
