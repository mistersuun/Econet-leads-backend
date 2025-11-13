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
import java.util.List;
import java.util.Map;

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
    private static final String DATA_SOURCE_NAME = "Données Montréal - Restaurants";

    private final CkanApiClient ckanApiClient;
    private final BusinessService businessService;
    private final DataSourceRepository dataSourceRepository;
    private final ScraperJobRepository scraperJobRepository;

    /**
     * Import all Restaurant data from Données Montréal
     */
    public ScraperJob importRestaurantData() {
        log.info("Starting Restaurant import from Données Montréal...");

        DataSource dataSource = getOrCreateDataSource();
        ScraperJob job = createJob(dataSource);

        try {
            List<Map<String, Object>> records = ckanApiClient.fetchAllRecords(
                    CKAN_BASE_URL,
                    RESTAURANT_RESOURCE_ID,
                    100
            );

            log.info("Processing {} Restaurant records...", records.size());

            int processed = 0;
            int added = 0;
            int updated = 0;
            StringBuilder errors = new StringBuilder();

            for (Map<String, Object> record : records) {
                try {
                    Business business = mapRestaurantRecordToBusiness(record);
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
                    log.error("Error processing Restaurant record: {}", e.getMessage());
                    errors.append(String.format("Record %d: %s\n", processed, e.getMessage()));
                }
            }

            job = updateJobSuccess(job, processed, added, updated, errors.toString());
            updateDataSource(dataSource, processed);

            log.info("Restaurant import completed: {} processed, {} added, {} updated", processed, added, updated);

        } catch (Exception e) {
            log.error("Restaurant import failed: {}", e.getMessage(), e);
            job = updateJobFailure(job, e.getMessage());
        }

        return job;
    }

    @Transactional
    private DataSource getOrCreateDataSource() {
        return dataSourceRepository
                .findBySourceName(DATA_SOURCE_NAME)
                .orElseGet(() -> {
                    DataSource ds = new DataSource();
                    ds.setSourceName(DATA_SOURCE_NAME);
                    ds.setSourceType(DataSource.SourceType.CKAN_API);
                    ds.setSourceUrl("https://donnees.montreal.ca/dataset/permis-restaurants");
                    ds.setSyncFrequency(DataSource.SyncFrequency.WEEKLY);
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
