package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.ScraperJobRepository;
import com.econet.leads.service.BusinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for importing Healthcare Facilities from Statistics Canada
 * Note: This is a placeholder implementation - actual CSV URL needs to be configured
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatCanHealthcareFacilitiesService {

    private static final String DATA_SOURCE_NAME = "Statistics Canada - Healthcare Facilities";

    private final BusinessService businessService;
    private final DataSourceRepository dataSourceRepository;
    private final ScraperJobRepository scraperJobRepository;

    /**
     * Import Healthcare Facilities data from Statistics Canada
     * Note: This is a mock implementation. Real implementation would download and parse CSV
     */
    public ScraperJob importHealthcareFacilitiesData() {
        log.info("Starting Healthcare Facilities import from Statistics Canada...");

        DataSource dataSource = getOrCreateDataSource();
        ScraperJob job = createJob(dataSource);

        try {
            // Mock data - in real implementation, download and parse CSV from StatCan
            List<Map<String, Object>> records = fetchMockHealthcareFacilities();

            log.info("Processing {} Healthcare Facility records...", records.size());

            int processed = 0;
            int added = 0;
            int updated = 0;
            StringBuilder errors = new StringBuilder();

            for (Map<String, Object> record : records) {
                try {
                    Business business = mapHealthcareFacilityToBusiness(record);
                    Business result = businessService.findOrCreate(business);

                    if (result.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(5))) {
                        added++;
                    } else {
                        updated++;
                    }

                    processed++;

                    if (processed % 50 == 0) {
                        log.info("Processed {}/{} records", processed, records.size());
                    }

                } catch (Exception e) {
                    log.error("Error processing Healthcare Facility record: {}", e.getMessage());
                    errors.append(String.format("Record %d: %s\n", processed, e.getMessage()));
                }
            }

            job = updateJobSuccess(job, processed, added, updated, errors.toString());
            updateDataSource(dataSource, processed);

            log.info("Healthcare Facilities import completed: {} processed, {} added, {} updated",
                    processed, added, updated);

        } catch (Exception e) {
            log.error("Healthcare Facilities import failed: {}", e.getMessage(), e);
            job = updateJobFailure(job, e.getMessage());
        }

        return job;
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

    @Transactional
    private DataSource getOrCreateDataSource() {
        return dataSourceRepository
                .findBySourceName(DATA_SOURCE_NAME)
                .orElseGet(() -> {
                    DataSource ds = new DataSource();
                    ds.setSourceName(DATA_SOURCE_NAME);
                    ds.setSourceType(DataSource.SourceType.CSV_DOWNLOAD);
                    ds.setSourceUrl("https://www150.statcan.gc.ca/n1/en/catalogue/82-006-X");
                    ds.setSyncFrequency(DataSource.SyncFrequency.MONTHLY);
                    ds.setActive(true);
                    return dataSourceRepository.save(ds);
                });
    }

    @Transactional
    private ScraperJob createJob(DataSource dataSource) {
        ScraperJob job = new ScraperJob();
        job.setSource(dataSource);
        job.setJobType(ScraperJob.JobType.FULL_SYNC);
        job.setStatus(ScraperJob.JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        return scraperJobRepository.save(job);
    }

    @Transactional
    private ScraperJob updateJobSuccess(ScraperJob job, int processed, int added, int updated, String errors) {
        job.setStatus(ScraperJob.JobStatus.COMPLETED);
        job.setCompletedAt(LocalDateTime.now());
        job.setRecordsProcessed(processed);
        job.setRecordsAdded(added);
        job.setRecordsUpdated(updated);
        job.setErrors(errors);
        return scraperJobRepository.save(job);
    }

    @Transactional
    private ScraperJob updateJobFailure(ScraperJob job, String error) {
        job.setStatus(ScraperJob.JobStatus.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        job.setErrors(error);
        return scraperJobRepository.save(job);
    }

    @Transactional
    private void updateDataSource(DataSource dataSource, int recordsCount) {
        dataSource.setLastSync(LocalDateTime.now());
        dataSource.setRecordsCount(recordsCount);
        dataSourceRepository.save(dataSource);
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
