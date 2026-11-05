package com.econet.leads.controller;

import com.econet.leads.dto.ScriptDTOs;
import com.econet.leads.service.ScriptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
@Tag(name = "Scripts", description = "Call scripts per business type and language")
public class ScriptController {

    private final ScriptService scriptService;

    @GetMapping
    @Operation(summary = "Best matching script", description = "Exact businessType + language, else the language default (fallback: true). Placeholders are substituted client-side.")
    public ResponseEntity<ScriptDTOs.Script> bestMatch(@RequestParam(required = false) String businessType,
                                                       @RequestParam(required = false) String language) {
        return ResponseEntity.ok(scriptService.bestMatch(businessType, language));
    }

    @GetMapping("/all")
    @Operation(summary = "All scripts")
    public ResponseEntity<List<ScriptDTOs.Script>> all() {
        return ResponseEntity.ok(scriptService.all());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a script (ADMIN)")
    public ResponseEntity<ScriptDTOs.Script> create(@Valid @RequestBody ScriptDTOs.Upsert request) {
        return ResponseEntity.ok(scriptService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a script (ADMIN)")
    public ResponseEntity<ScriptDTOs.Script> update(@PathVariable UUID id, @Valid @RequestBody ScriptDTOs.Upsert request) {
        return ResponseEntity.ok(scriptService.update(id, request));
    }
}
