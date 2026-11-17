package com.econet.leads.controller;

import com.econet.leads.dto.ClientDTOs.ClientDTO;
import com.econet.leads.dto.ClientDTOs.ClientsSummary;
import com.econet.leads.dto.ClientDTOs.ReviewConfig;
import com.econet.leads.dto.ClientDTOs.ReviewRequest;
import com.econet.leads.dto.ClientDTOs.ReviewResult;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.ClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads are open to every role; writes need USER/ADMIN and DELETE ADMIN (see SecurityConfig). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Clients", description = "Clients after the sale: recurring service, renewals, Google reviews")
public class ClientController {

    private final ClientService clientService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping("/clients")
    @Operation(summary = "Clients, newest first", description = "Optional status=active|paused|ended and businessId filters")
    public ResponseEntity<List<ClientDTO>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) UUID businessId) {
        return ResponseEntity.ok(clientService.list(status, businessId));
    }

    @GetMapping("/clients/review-config")
    @Operation(summary = "Is the Google review link / SMTP configured?")
    public ResponseEntity<ReviewConfig> reviewConfig() {
        return ResponseEntity.ok(clientService.reviewConfig());
    }

    @GetMapping("/clients/{id}")
    public ResponseEntity<ClientDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(clientService.get(id));
    }

    @PatchMapping("/clients/{id}")
    @Operation(summary = "Edit a client", description = "Any of service, frequency, pricePerVisit, startDate, contractEndDate, nextVisitAt, status, notes")
    public ResponseEntity<ClientDTO> patch(@PathVariable UUID id, @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(clientService.patch(id, patch));
    }

    @PostMapping("/clients/{id}/first-cleaning-done")
    @Operation(summary = "Marks the first cleaning as done (starts the 7-day Google review reminder)")
    public ResponseEntity<ClientDTO> firstCleaningDone(@PathVariable UUID id) {
        return ResponseEntity.ok(clientService.firstCleaningDone(id, authenticationFacade.getCurrentUser()));
    }

    @PostMapping("/clients/{id}/review-request")
    @Operation(summary = "Asks for a Google review", description = "channel=email sends it (SMTP, 503 otherwise); channel=link returns {url, message} to copy. Needs GOOGLE_REVIEW_URL (503 otherwise)")
    public ResponseEntity<ReviewResult> reviewRequest(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(clientService.requestReview(id, request.getChannel(), authenticationFacade.getCurrentUser()));
    }

    @DeleteMapping("/clients/{id}")
    @Operation(summary = "Deletes a client record (ADMIN)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        clientService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboard/clients")
    @Operation(summary = "Active clients, monthly recurring revenue, renewals in 30 days, reviews pending")
    public ResponseEntity<ClientsSummary> summary() {
        return ResponseEntity.ok(clientService.summary());
    }
}
