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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private static final String DATA_SOURCE_NAME = "Données Québec - CPE";

    private final CkanApiClient ckanApiClient;
    private final BusinessService businessService;
    private final DataSourceRepository dataSourceRepository;
    private final ScraperJobRepository scraperJobRepository;

    /**
     * Import all CPE data from Données Québec
     * Note: No @Transactional here - each record is saved in its own transaction
     * to prevent one bad record from rolling back the entire import
     */
    public ScraperJob importCpeData() {
        log.info("Starting CPE import from Données Québec...");

        // Get or create data source (in separate transaction)
        DataSource dataSource = getOrCreateDataSource();

        // Create job (in separate transaction)
        ScraperJob job = createJob(dataSource);

        try {
            // Fetch all CPE records
            List<Map<String, Object>> records = ckanApiClient.fetchAllRecords(
                    CKAN_BASE_URL,
                    CPE_RESOURCE_ID,
                    100
            );

            log.info("Processing {} CPE records...", records.size());

            int processed = 0;
            int added = 0;
            int updated = 0;
            StringBuilder errors = new StringBuilder();

            for (Map<String, Object> record : records) {
                try {
                    Business business = mapCpeRecordToBusiness(record);
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
                    log.error("Error processing CPE record: {}", e.getMessage());
                    errors.append(String.format("Record %d: %s\n", processed, e.getMessage()));
                }
            }

            // Update job status (in separate transaction)
            job = updateJobSuccess(job, processed, added, updated, errors.toString());

            // Update data source (in separate transaction)
            updateDataSource(dataSource, processed);

            log.info("CPE import completed: {} processed, {} added, {} updated", processed, added, updated);

        } catch (Exception e) {
            log.error("CPE import failed: {}", e.getMessage(), e);
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
                    ds.setSourceUrl("https://www.donneesquebec.ca/recherche/dataset/liste-centres-petite-enfance-subventionnes");
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
