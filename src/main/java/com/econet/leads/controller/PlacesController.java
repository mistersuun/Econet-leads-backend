package com.econet.leads.controller;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.PlacesDTOs;
import com.econet.leads.dto.ScraperJobDTO;
import com.econet.leads.service.PlacesService;
import com.econet.leads.service.PlacesUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Google Places enrichment of leads (optional: 503 while GOOGLE_PLACES_API_KEY is unset). USER/ADMIN for writes. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Places", description = "Google Places enrichment")
public class PlacesController {

    private final PlacesService placesService;
    private final PlacesUsageService usageService;

    @GetMapping("/api/places/status")
    @Operation(summary = "Google Places status", description = "configured, calls used today and the daily limit")
    public PlacesDTOs.Status status() {
        return new PlacesDTOs.Status(placesService.isConfigured(), usageService.usedToday(), usageService.dailyLimit(),
                PlacesDTOs.ATTRIBUTION);
    }

    @PostMapping("/api/businesses/{id}/enrich/places")
    @Operation(summary = "Enrich one lead with Google data", description = "Always refreshes. 503 not configured, 429 daily limit reached, 502 Google unreachable. Data is stored only when the match is confident (same phone or similar name).")
    public BusinessDTO enrich(@PathVariable UUID id) {
        return placesService.enrich(id);
    }

    @PostMapping("/api/businesses/enrich/places")
    @Operation(summary = "Enrich up to 50 leads in a background job", description = "202 ScraperJobDTO; leads with fresh data (< 30 days) are skipped. 503 not configured, 429 daily limit reached.")
    public ResponseEntity<ScraperJobDTO> enrichMany(@RequestBody PlacesDTOs.EnrichRequest request) {
        return ResponseEntity.accepted().body(ScraperJobDTO.fromEntity(placesService.startBulk(request.ids())));
    }
}
