package com.econet.leads.sms;

import com.econet.leads.exception.ApiException;
import com.econet.leads.security.AuthenticationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "SMS and integrations", description = "Follow-up texts, owner alerts, provider status")
public class SmsController {

    private final FollowUpSmsService followUps;
    private final IntegrationsService integrations;
    private final AlertService alerts;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/api/leads/{id}/sms")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Send a follow-up text to a prospect",
            description = "{ templateKey: missed_call|after_voicemail|quote_followup, language?, consent: true } -> 201 { status }. "
                    + "400 without consent, 503 when Twilio is not configured, 429 beyond 20 per user per hour.")
    public ResponseEntity<SmsDTOs.FollowUpResponse> send(@PathVariable UUID id, @Valid @RequestBody SmsDTOs.FollowUpRequest request) {
        String status = followUps.send(id, request.getTemplateKey(), request.getLanguage(), request.getConsent(),
                authenticationFacade.getCurrentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SmsDTOs.FollowUpResponse(status));
    }

    @GetMapping("/api/leads/{id}/sms/preview")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Rendered text of a follow-up template for this lead")
    public ResponseEntity<SmsDTOs.Preview> preview(@PathVariable UUID id, @RequestParam String templateKey,
                                                   @RequestParam(required = false) String language) {
        return ResponseEntity.ok(new SmsDTOs.Preview(
                followUps.preview(id, templateKey, language, authenticationFacade.getCurrentUser())));
    }

    @GetMapping("/api/integrations/status")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Which optional providers are configured")
    public ResponseEntity<SmsDTOs.IntegrationsStatus> status() {
        return ResponseEntity.ok(integrations.status());
    }

    @PostMapping("/api/integrations/test-alert")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Send a test alert to ALERT_PHONE / ALERT_EMAIL (ADMIN)")
    public ResponseEntity<SmsDTOs.TestAlertResponse> testAlert() {
        AlertService.TestResult r = alerts.sendTest();
        boolean smsPossible = r.smsConfigured() && r.smsTargets() > 0;
        boolean emailPossible = r.emailConfigured() && r.emailTargets() > 0;
        if (!smsPossible && !emailPossible) {
            throw ApiException.serviceUnavailable("Aucune alerte à tester : définissez ALERT_PHONE (avec les variables TWILIO_*) "
                    + "ou ALERT_EMAIL (avec SMTP_HOST)");
        }
        return ResponseEntity.ok(new SmsDTOs.TestAlertResponse(r.smsConfigured(), r.smsTargets(), r.smsSent(),
                r.emailConfigured(), r.emailTargets(), r.emailSent()));
    }
}
