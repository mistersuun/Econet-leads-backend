package com.econet.leads.controller;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.ClaimDTO;
import com.econet.leads.dto.EmailPreviewDTO;
import com.econet.leads.dto.EmailSendRequest;
import com.econet.leads.dto.EmailSendResponse;
import com.econet.leads.dto.LogCallRequest;
import com.econet.leads.dto.LogCallResponse;
import com.econet.leads.dto.SnoozeRequest;
import com.econet.leads.model.User;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.EmailService;
import com.econet.leads.service.LeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
@Tag(name = "Leads", description = "Calling workflow: call queue, claims, call logging, snooze, emails")
public class LeadController {

    private final LeadService leadService;
    private final EmailService emailService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping("/queue")
    @Operation(summary = "Call queue", description = "Follow-ups due (oldest first), then NEW leads with a phone by priority score. "
            + "Excludes WON/LOST/DO_NOT_CALL, leads assigned to someone else and leads claimed by another user. "
            + "Optional filters: businessType, city, sourceIn (repeatable), minQualityScore, followUpsOnly, newOnly.")
    public ResponseEntity<List<BusinessDTO>> getQueue(@RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) List<String> businessType,
                                                      @RequestParam(required = false) List<String> city,
                                                      @RequestParam(required = false) List<String> sourceIn,
                                                      @RequestParam(required = false) Integer minQualityScore,
                                                      @RequestParam(defaultValue = "false") boolean followUpsOnly,
                                                      @RequestParam(defaultValue = "false") boolean newOnly) {
        User user = authenticationFacade.getCurrentUser();
        LeadService.QueueFilters filters = new LeadService.QueueFilters(
                businessType, city, sourceIn, minQualityScore, followUpsOnly, newOnly);
        return ResponseEntity.ok(leadService.getQueue(user.getId(), size, filters));
    }

    @PostMapping("/{id}/claim")
    @Operation(summary = "Claim a lead", description = "Reserves the lead for 10 minutes (call again to refresh); 409 with claimedBy when another user holds it")
    public ResponseEntity<ClaimDTO> claim(@PathVariable UUID id) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(leadService.claim(id, user));
    }

    @DeleteMapping("/{id}/claim")
    @Operation(summary = "Release a claim", description = "Releases the caller's own claim; 204 either way")
    public ResponseEntity<Void> releaseClaim(@PathVariable UUID id) {
        User user = authenticationFacade.getCurrentUser();
        leadService.releaseClaim(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/calls")
    @Operation(summary = "Log a call", description = "Creates the call contact (with durationSeconds), updates status, counters, assignment, "
            + "next follow-up (or snoozeUntil), merges the qualification and releases the claim")
    public ResponseEntity<LogCallResponse> logCall(@PathVariable UUID id, @Valid @RequestBody LogCallRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(leadService.logCall(id, request, user.getId()));
    }

    @PostMapping("/{id}/snooze")
    @Operation(summary = "Snooze a lead", description = "Sets nextFollowUpAt without changing the status, writes a 'Reporté au …' note and releases the claim (NEW, CONTACTED, INTERESTED, QUOTE_SENT)")
    public ResponseEntity<BusinessDTO> snooze(@PathVariable UUID id, @Valid @RequestBody SnoozeRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(leadService.snooze(id, request, user.getId()));
    }

    @PostMapping("/{id}/emails/preview")
    @Operation(summary = "Preview a follow-up email", description = "Renders the template without sending")
    public ResponseEntity<EmailPreviewDTO> previewEmail(@PathVariable UUID id, @Valid @RequestBody EmailSendRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(emailService.preview(id, request, user));
    }

    @PostMapping("/{id}/emails")
    @Operation(summary = "Send a follow-up email", description = "Renders, sends (ICS invite attached when visitAt is set), records an EMAIL contact. 503 when SMTP is not configured, 429 above 20 emails/hour")
    public ResponseEntity<EmailSendResponse> sendEmail(@PathVariable UUID id, @Valid @RequestBody EmailSendRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(emailService.send(id, request, user));
    }
}
