package com.econet.leads.controller;

import com.econet.leads.dto.ImportRunRequest;
import com.econet.leads.dto.ScraperJobDTO;
import com.econet.leads.integration.fileimport.FileImportService;
import com.econet.leads.integration.fileimport.ImportFileStore;
import com.econet.leads.security.AuthenticationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Import leads from a CSV / TSV / text / XLSX file: upload, map columns, run (USER/ADMIN). */
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
@Tag(name = "Imports", description = "File import of leads")
public class ImportController {

    private final FileImportService fileImportService;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping(value = "/files", consumes = "multipart/form-data")
    @Operation(summary = "Upload a file", description = "Multipart field 'file' (.csv .tsv .txt .xlsx, max 10 MB). Returns the detected columns, 5 sample rows and a suggested mapping; the file is kept for 1 hour.")
    public ResponseEntity<ImportFileStore.UploadInfo> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(fileImportService.upload(file));
    }

    @GetMapping("/{importId}")
    @Operation(summary = "Upload metadata", description = "Same body as the upload response; 404 once expired or run")
    public ResponseEntity<ImportFileStore.UploadInfo> get(@PathVariable String importId) {
        return ResponseEntity.ok(fileImportService.info(importId));
    }

    @PostMapping("/{importId}/run")
    @Operation(summary = "Run the import", description = "Starts the background job (202 + ScraperJobDTO); poll GET /api/scraper-jobs/{id}")
    public ResponseEntity<ScraperJobDTO> run(@PathVariable String importId, @RequestBody ImportRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ScraperJobDTO.fromEntity(fileImportService.run(importId, request, authenticationFacade.getCurrentUser())));
    }
}
