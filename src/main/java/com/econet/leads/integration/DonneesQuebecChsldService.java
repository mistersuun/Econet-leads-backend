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
    private static final String DATA_SOURCE_NAME = "Données Québec - CHSLD";

    private final CkanApiClient ckanApiClient;
    private final BusinessService businessService;
    private final DataSourceRepository dataSourceRepository;
    private final ScraperJobRepository scraperJobRepository;

    /**
     * Import all CHSLD data from Données Québec
     * Note: No @Transactional here - each record is saved in its own transaction
     */
    public ScraperJob importChsldData() {
        log.info("Starting CHSLD import from Données Québec...");

        // Get or create data source (in separate transaction)
        DataSource dataSource = getOrCreateDataSource();

        // Create job (in separate transaction)
        ScraperJob job = createJob(dataSource);

        try {
            // Fetch all establishment records
            List<Map<String, Object>> allRecords = ckanApiClient.fetchAllRecords(
                    CKAN_BASE_URL,
                    CHSLD_RESOURCE_ID,
                    100
            );

            log.info("Fetched {} total establishment records, filtering for CHSLD...", allRecords.size());

            // Filter only CHSLD records (CHSLD column = "Oui")
            List<Map<String, Object>> chsldRecords = allRecords.stream()
                    .filter(record -> "Oui".equalsIgnoreCase(getString(record, "CHSLD")))
                    .collect(Collectors.toList());

            log.info("Processing {} CHSLD records...", chsldRecords.size());

            int processed = 0;
            int added = 0;
            int updated = 0;
            StringBuilder errors = new StringBuilder();

            for (Map<String, Object> record : chsldRecords) {
                try {
                    Business business = mapChsldRecordToBusiness(record);
                    Business result = businessService.findOrCreate(business);

                    if (result.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(5))) {
                        added++;
                    } else {
                        updated++;
                    }

                    processed++;

                    if (processed % 50 == 0) {
                        log.info("Processed {}/{} CHSLD records", processed, chsldRecords.size());
                    }

                } catch (Exception e) {
                    log.error("Error processing CHSLD record: {}", e.getMessage());
                    errors.append(String.format("Record %d: %s\n", processed, e.getMessage()));
                }
            }

            // Update job status (in separate transaction)
            job = updateJobSuccess(job, processed, added, updated, errors.toString());

            // Update data source (in separate transaction)
            updateDataSource(dataSource, processed);

            log.info("CHSLD import completed: {} processed, {} added, {} updated", processed, added, updated);

        } catch (Exception e) {
            log.error("CHSLD import failed: {}", e.getMessage(), e);
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
                    ds.setSourceUrl("https://www.donneesquebec.ca/recherche/dataset/51998b55-7d4c-4381-8c20-0ac1cd9c1b87");
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
