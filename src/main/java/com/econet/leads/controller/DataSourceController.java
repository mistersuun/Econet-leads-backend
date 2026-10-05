package com.econet.leads.controller;

import com.econet.leads.dto.ScraperJobDTO;
import com.econet.leads.integration.ImportAlreadyRunningException;
import com.econet.leads.integration.ImportService;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing data sources and triggering imports.
 * Imports run in the background: the trigger endpoints return the PENDING ScraperJob immediately.
 */
@RestController
@RequestMapping("/api/data-sources")
@RequiredArgsConstructor
@Slf4j
public class DataSourceController {

    private final DataSourceRepository dataSourceRepository;
    private final ImportService importService;

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
     * Trigger a background import for a specific data source.
     * 202 + ScraperJobDTO (PENDING), 404 unknown source, 400 inactive/unsupported, 409 already running.
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> triggerImport(@PathVariable UUID id) {
        return dataSourceRepository.findById(id)
                .map(this::startImport)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Data source not found: " + id)));
    }

    /**
     * Trigger a background import by data source name
     */
    @PostMapping("/import/{sourceName}")
    public ResponseEntity<?> triggerImportByName(@PathVariable String sourceName) {
        return dataSourceRepository.findBySourceName(sourceName)
                .map(this::startImport)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Data source not found: " + sourceName)));
    }

    /**
     * Trigger background imports for all active data sources. Sources that already have an import
     * running, or that cannot be imported, are reported in "skipped".
     */
    @PostMapping("/import-all")
    public ResponseEntity<?> triggerAllImports() {
        List<DataSource> activeSources = dataSourceRepository.findByActive(true);

        if (activeSources.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "No active data sources to import",
                    "jobs", List.of(), "skipped", List.of()));
        }

        List<ScraperJobDTO> jobs = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();
        for (DataSource source : activeSources) {
            try {
                jobs.add(ScraperJobDTO.fromEntity(importService.startImport(source)));
            } catch (Exception e) {
                log.warn("Skipping import of {}: {}", source.getSourceName(), e.getMessage());
                skipped.add(Map.of("sourceName", source.getSourceName(),
                        "reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "Started imports for " + jobs.size() + " data sources",
                "jobs", jobs,
                "skipped", skipped
        ));
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

    private ResponseEntity<?> startImport(DataSource dataSource) {
        if (!Boolean.TRUE.equals(dataSource.getActive())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Data source is not active"));
        }
        try {
            ScraperJob job = importService.startImport(dataSource);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ScraperJobDTO.fromEntity(job));
        } catch (ImportAlreadyRunningException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
