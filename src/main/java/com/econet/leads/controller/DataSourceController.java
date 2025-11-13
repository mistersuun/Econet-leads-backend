package com.econet.leads.controller;

import com.econet.leads.integration.DonneesQuebecChsldService;
import com.econet.leads.integration.DonneesQuebecCpeService;
import com.econet.leads.integration.DonneesMontrealRestaurantService;
import com.econet.leads.integration.StatCanHealthcareFacilitiesService;
import com.econet.leads.integration.PagesJaunesScraperService;
import com.econet.leads.integration.GenericCkanImportService;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing data sources and triggering imports
 */
@RestController
@RequestMapping("/api/data-sources")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class DataSourceController {

    private final DataSourceRepository dataSourceRepository;
    private final DonneesQuebecCpeService cpeService;
    private final DonneesQuebecChsldService chsldService;
    private final DonneesMontrealRestaurantService montrealRestaurantService;
    private final StatCanHealthcareFacilitiesService statCanHealthcareService;
    private final PagesJaunesScraperService pagesJaunesScraperService;
    private final GenericCkanImportService genericCkanImportService;

    /**
     * Get all data sources
     */
    @GetMapping
    public ResponseEntity<List<DataSource>> getAllDataSources(
            @RequestParam(required = false) Boolean active) {

        List<DataSource> dataSources;

        if (active != null) {
            dataSources = dataSourceRepository.findByActive(active);
        } else {
            dataSources = dataSourceRepository.findAll();
        }

        return ResponseEntity.ok(dataSources);
    }

    /**
     * Get a specific data source by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataSource> getDataSourceById(@PathVariable UUID id) {
        return dataSourceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get a data source by name
     */
    @GetMapping("/by-name/{name}")
    public ResponseEntity<DataSource> getDataSourceByName(@PathVariable String name) {
        return dataSourceRepository.findBySourceName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Trigger import for a specific data source
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> triggerImport(@PathVariable UUID id) {

        DataSource dataSource = dataSourceRepository.findById(id)
                .orElse(null);

        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }

        if (!dataSource.getActive()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Data source is not active"));
        }

        try {
            ScraperJob job = triggerImportForSource(dataSource);
            return ResponseEntity.ok(job);
        } catch (Exception e) {
            log.error("Error triggering import for source {}: {}", dataSource.getSourceName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to trigger import: " + e.getMessage()));
        }
    }

    /**
     * Trigger import by data source name
     */
    @PostMapping("/import/{sourceName}")
    public ResponseEntity<?> triggerImportByName(@PathVariable String sourceName) {

        DataSource dataSource = dataSourceRepository.findBySourceName(sourceName)
                .orElse(null);

        if (dataSource == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Data source not found: " + sourceName));
        }

        if (!dataSource.getActive()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Data source is not active"));
        }

        try {
            ScraperJob job = triggerImportForSource(dataSource);
            return ResponseEntity.ok(job);
        } catch (Exception e) {
            log.error("Error triggering import for source {}: {}", dataSource.getSourceName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to trigger import: " + e.getMessage()));
        }
    }

    /**
     * Trigger imports for all active data sources
     */
    @PostMapping("/import-all")
    public ResponseEntity<?> triggerAllImports() {

        List<DataSource> activeSources = dataSourceRepository.findByActive(true);

        if (activeSources.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "No active data sources to import"));
        }

        try {
            List<ScraperJob> jobs = activeSources.stream()
                    .map(this::triggerImportForSource)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "message", "Triggered imports for " + jobs.size() + " data sources",
                    "jobs", jobs
            ));
        } catch (Exception e) {
            log.error("Error triggering all imports: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to trigger imports: " + e.getMessage()));
        }
    }

    /**
     * Update data source activation status
     */
    @PatchMapping("/{id}/active")
    public ResponseEntity<?> updateActiveStatus(
            @PathVariable UUID id,
            @RequestParam Boolean active) {

        DataSource dataSource = dataSourceRepository.findById(id)
                .orElse(null);

        if (dataSource == null) {
            return ResponseEntity.notFound().build();
        }

        dataSource.setActive(active);
        dataSourceRepository.save(dataSource);

        return ResponseEntity.ok(dataSource);
    }

    /**
     * Helper method to trigger import based on data source type
     * Uses specific services for legacy sources (CPE, CHSLD)
     * Uses GenericCkanImportService for all other CKAN_API sources
     */
    private ScraperJob triggerImportForSource(DataSource dataSource) {
        log.info("Triggering import for data source: {}", dataSource.getSourceName());

        // Handle legacy specific services
        return switch (dataSource.getSourceName()) {
            case "Données Québec - CPE" -> cpeService.importCpeData();
            case "Données Québec - CHSLD" -> chsldService.importChsldData();
            case "Données Montréal - Restaurants" -> montrealRestaurantService.importRestaurantData();
            case "Statistics Canada - Healthcare Facilities" -> statCanHealthcareService.importHealthcareFacilitiesData();
            case "Pages Jaunes - Manual Scraping" -> pagesJaunesScraperService.scrapeBusinesses();
            default -> {
                // Use generic service for all CKAN_API sources with config
                if (dataSource.getSourceType() == DataSource.SourceType.CKAN_API && dataSource.getConfig() != null) {
                    yield genericCkanImportService.importFromCkan(dataSource);
                }
                throw new IllegalArgumentException(
                        "Source de données non supportée: " + dataSource.getSourceName()
                );
            }
        };
    }
}
