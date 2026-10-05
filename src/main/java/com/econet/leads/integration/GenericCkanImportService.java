package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic service for importing data from any CKAN API endpoint
 * Configuration is read from DataSource.config JSON field
 *
 * Expected config structure:
 * {
 *   "resourceId": "89af3537-4506-488c-8d0e-6d85b4033a0e",
 *   "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
 *   "batchSize": 100,
 *   "businessType": "CPE",
 *   "fieldMapping": {
 *     "businessName": "NOM",
 *     "addressStreet": "ADRESSE",
 *     "addressCity": "NOM_MUN_COMPO",
 *     "addressProvince": "QC",
 *     "postalCode": "CODE_POSTAL_COMPO",
 *     "phone": "telephone1",
 *     "email": "INTERNET",
 *     "website": "SITE_WEB",
 *     "externalId": "_id"
 *   }
 * }
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenericCkanImportService {

    private final CkanApiClient ckanApiClient;

    /**
     * Fetch all records of a CKAN source and map them to Business candidates using its config.
     */
    public List<Business> fetchBusinesses(DataSource dataSource, CkanImportConfig config) {
        List<Map<String, Object>> records = ckanApiClient.fetchAllRecords(
                config.getCkanBaseUrl(),
                config.getResourceId(),
                config.getBatchSize()
        );
        log.info("Fetched {} records from {}", records.size(), dataSource.getSourceName());
        return records.stream()
                .map(record -> mapRecordToBusiness(record, config, dataSource))
                .collect(Collectors.toList());
    }

    /**
     * Parse and validate the CKAN import configuration of a data source.
     *
     * @throws IllegalArgumentException if the configuration is missing or incomplete
     */
    public CkanImportConfig parseConfig(DataSource dataSource) {
        Map<String, Object> configMap = dataSource.getConfig();
        if (configMap == null || configMap.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration for " + dataSource.getSourceName() + ": config is empty");
        }

        CkanImportConfig config = new CkanImportConfig();
        config.setResourceId(getStringFromMap(configMap, "resourceId"));
        config.setCkanBaseUrl(getStringFromMap(configMap, "ckanBaseUrl", "https://www.donneesquebec.ca/recherche/api/3/action/"));
        config.setBatchSize(getIntFromMap(configMap, "batchSize", 100));
        config.setBusinessType(getStringFromMap(configMap, "businessType"));

        // Parse field mapping
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldMapping = (Map<String, Object>) configMap.get("fieldMapping");
        if (fieldMapping != null) {
            config.setBusinessNameField(getStringFromMap(fieldMapping, "businessName"));
            config.setAddressStreetField(getStringFromMap(fieldMapping, "addressStreet"));
            config.setAddressCityField(getStringFromMap(fieldMapping, "addressCity"));
            config.setAddressProvinceField(getStringFromMap(fieldMapping, "addressProvince"));
            config.setAddressProvinceDefault(getStringFromMap(fieldMapping, "addressProvinceDefault", "QC"));
            config.setPostalCodeField(getStringFromMap(fieldMapping, "postalCode"));
            config.setPhoneField(getStringFromMap(fieldMapping, "phone"));
            config.setEmailField(getStringFromMap(fieldMapping, "email"));
            config.setWebsiteField(getStringFromMap(fieldMapping, "website"));
            config.setExternalIdField(getStringFromMap(fieldMapping, "externalId", "_id"));
        }

        if (config.getResourceId() == null || config.getResourceId().isBlank()
                || "to_be_configured".equals(config.getResourceId())) {
            throw new IllegalArgumentException("Invalid configuration for " + dataSource.getSourceName() + ": resourceId is missing");
        }
        if (config.getBusinessNameField() == null) {
            throw new IllegalArgumentException("Invalid configuration for " + dataSource.getSourceName() + ": fieldMapping.businessName is missing");
        }
        if (config.getBusinessType() == null) {
            throw new IllegalArgumentException("Invalid configuration for " + dataSource.getSourceName() + ": businessType is missing");
        }
        return config;
    }

    /**
     * Get string value from map
     */
    private String getStringFromMap(Map<String, Object> map, String key) {
        return getStringFromMap(map, key, null);
    }

    /**
     * Get string value from map with default
     */
    private String getStringFromMap(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }

    /**
     * Get int value from map with default
     */
    private int getIntFromMap(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Map a CKAN record to Business entity using field mapping
     */
    private Business mapRecordToBusiness(Map<String, Object> record, CkanImportConfig config, DataSource dataSource) {
        Business business = new Business();

        // Name
        business.setBusinessName(getFieldValue(record, config.getBusinessNameField()));
        business.setBusinessType(config.getBusinessType());

        // Address
        business.setAddressStreet(getFieldValue(record, config.getAddressStreetField()));
        business.setAddressCity(getFieldValue(record, config.getAddressCityField()));

        String province = getFieldValue(record, config.getAddressProvinceField());
        business.setAddressProvince(province != null ? province : config.getAddressProvinceDefault());

        business.setPostalCode(getFieldValue(record, config.getPostalCodeField()));

        // Contact
        business.setPhone(getFieldValue(record, config.getPhoneField()));
        business.setEmail(getFieldValue(record, config.getEmailField()));
        business.setWebsite(getFieldValue(record, config.getWebsiteField()));

        // Metadata
        business.setDataSource(dataSource.getSourceName());
        business.setSourceUrl(dataSource.getSourceUrl());
        business.setExternalId(getFieldValue(record, config.getExternalIdField()));

        return business;
    }

    /**
     * Get field value from record, handling null/empty
     */
    private String getFieldValue(Map<String, Object> record, String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return null;
        }

        Object value = record.get(fieldName);
        if (value == null) return null;

        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    /**
     * Inner class to hold parsed configuration
     */
    static class CkanImportConfig {
        private String resourceId;
        private String ckanBaseUrl;
        private int batchSize;
        private String businessType;

        // Field mappings
        private String businessNameField;
        private String addressStreetField;
        private String addressCityField;
        private String addressProvinceField;
        private String addressProvinceDefault;
        private String postalCodeField;
        private String phoneField;
        private String emailField;
        private String websiteField;
        private String externalIdField;

        // Getters and setters
        public String getResourceId() { return resourceId; }
        public void setResourceId(String resourceId) { this.resourceId = resourceId; }

        public String getCkanBaseUrl() { return ckanBaseUrl; }
        public void setCkanBaseUrl(String ckanBaseUrl) { this.ckanBaseUrl = ckanBaseUrl; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public String getBusinessType() { return businessType; }
        public void setBusinessType(String businessType) { this.businessType = businessType; }

        public String getBusinessNameField() { return businessNameField; }
        public void setBusinessNameField(String businessNameField) { this.businessNameField = businessNameField; }

        public String getAddressStreetField() { return addressStreetField; }
        public void setAddressStreetField(String addressStreetField) { this.addressStreetField = addressStreetField; }

        public String getAddressCityField() { return addressCityField; }
        public void setAddressCityField(String addressCityField) { this.addressCityField = addressCityField; }

        public String getAddressProvinceField() { return addressProvinceField; }
        public void setAddressProvinceField(String addressProvinceField) { this.addressProvinceField = addressProvinceField; }

        public String getAddressProvinceDefault() { return addressProvinceDefault; }
        public void setAddressProvinceDefault(String addressProvinceDefault) { this.addressProvinceDefault = addressProvinceDefault; }

        public String getPostalCodeField() { return postalCodeField; }
        public void setPostalCodeField(String postalCodeField) { this.postalCodeField = postalCodeField; }

        public String getPhoneField() { return phoneField; }
        public void setPhoneField(String phoneField) { this.phoneField = phoneField; }

        public String getEmailField() { return emailField; }
        public void setEmailField(String emailField) { this.emailField = emailField; }

        public String getWebsiteField() { return websiteField; }
        public void setWebsiteField(String websiteField) { this.websiteField = websiteField; }

        public String getExternalIdField() { return externalIdField; }
        public void setExternalIdField(String externalIdField) { this.externalIdField = externalIdField; }
    }
}
