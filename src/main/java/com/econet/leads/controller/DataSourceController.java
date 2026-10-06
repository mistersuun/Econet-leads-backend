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
import org.springframework.web.multipart.MultipartFile;

import com.econet.leads.integration.support.ImportFiles;

import java.io.IOException;
import java.nio.file.Path;

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
     * Upload the Registre des entreprises ZIP (multipart field "file") and import it in the
     * background: 202 + ScraperJobDTO. Allowed even while the source is inactive (uploading is how
     * an admin sets it up without the 225 MB download). 400 if the source is not the register or the
     * file is not a ZIP, 404 unknown source, 409 already running, 413 over the size limit.
     */
    @PostMapping(value = "/{id}/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadAndImport(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        DataSource dataSource = dataSourceRepository.findById(id).orElse(null);
        if (dataSource == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Data source not found: " + id));
        }
        if (!com.econet.leads.integration.QuebecBusinessRegisterImporter.IMPORTER.equals(ImportService.importerOf(dataSource))) {
            return ResponseEntity.badRequest().body(Map.of("error", "File upload is only supported for the business register source"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing or empty multipart field 'file'"));
        }
        if (importService.isImportRunning(dataSource)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", new ImportAlreadyRunningException(dataSource.getSourceName()).getMessage()));
        }
        Path target = ImportFiles.newTempFile("upload-", ".zip");
        try {
            file.transferTo(target);
            if (!looksLikeZip(target)) {
                ImportFiles.deleteQuietly(target);
                return ResponseEntity.badRequest().body(Map.of("error", "The uploaded file is not a ZIP archive"));
            }
            log.info("Received {} ({} bytes) for {}", file.getOriginalFilename(), file.getSize(), dataSource.getSourceName());
            ScraperJob job = importService.startImportFromFile(dataSource, target);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ScraperJobDTO.fromEntity(job));
        } catch (ImportAlreadyRunningException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException | RuntimeException e) {
            ImportFiles.deleteQuietly(target);
            throw e;
        }
    }

    private static boolean looksLikeZip(Path file) throws IOException {
        try (var in = java.nio.file.Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        }
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
                String reason = e instanceof org.springframework.web.server.ResponseStatusException rse && rse.getReason() != null
                        ? rse.getReason()
                        : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                skipped.add(Map.of("sourceName", source.getSourceName(), "reason", reason));
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
