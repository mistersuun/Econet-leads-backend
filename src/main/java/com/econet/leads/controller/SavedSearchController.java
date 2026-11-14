package com.econet.leads.controller;

import com.econet.leads.dto.SavedSearchDTOs;
import com.econet.leads.dto.ScraperJobDTO;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.SavedSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Saved discovery searches (USER/ADMIN; DELETE is ADMIN only, enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/saved-searches")
@RequiredArgsConstructor
@Tag(name = "Saved searches", description = "Recurring OpenStreetMap discovery")
public class SavedSearchController {

    private final SavedSearchService service;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping
    public List<SavedSearchDTOs.Response> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavedSearchDTOs.Response create(@RequestBody SavedSearchDTOs.Request request) {
        return service.create(request, authenticationFacade.getCurrentUser());
    }

    @PutMapping("/{id}")
    public SavedSearchDTOs.Response update(@PathVariable UUID id, @RequestBody SavedSearchDTOs.Request request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    @Operation(summary = "Run now", description = "202 ScraperJobDTO; only candidates that are not leads yet are imported. 409 when already running.")
    public ResponseEntity<ScraperJobDTO> run(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(ScraperJobDTO.fromEntity(service.runNow(id)));
    }
}
