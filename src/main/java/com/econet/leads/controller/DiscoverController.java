package com.econet.leads.controller;

import com.econet.leads.dto.DiscoverDTOs;
import com.econet.leads.integration.osm.OsmDiscoveryService;
import com.econet.leads.security.AuthenticationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Discover businesses on the map (OpenStreetMap, ODbL). USER/ADMIN. */
@RestController
@RequestMapping("/api/discover")
@RequiredArgsConstructor
@Tag(name = "Discover", description = "OpenStreetMap business discovery")
public class DiscoverController {

    private final OsmDiscoveryService discoveryService;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/osm")
    @Operation(summary = "Search OpenStreetMap", description = "Candidates for a city and categories; nothing is saved. 502 {error} when the OSM services are unreachable.")
    public ResponseEntity<DiscoverDTOs.SearchResponse> search(@RequestBody DiscoverDTOs.SearchRequest request) {
        return ResponseEntity.ok(discoveryService.search(request.city(), request.categories(), request.limit()));
    }

    @PostMapping("/osm/import")
    @Operation(summary = "Import candidates as leads", description = "dataSource OpenStreetMap, externalId = osmId; candidates that already exist are skipped")
    public ResponseEntity<DiscoverDTOs.ImportResponse> importCandidates(@RequestBody DiscoverDTOs.ImportRequest request) {
        return ResponseEntity.ok(discoveryService.importCandidates(request.candidates(),
                Boolean.TRUE.equals(request.assignToMe()), authenticationFacade.getCurrentUser()));
    }
}
