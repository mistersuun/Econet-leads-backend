package com.econet.leads.controller;

import com.econet.leads.dto.EmailDTOs;
import com.econet.leads.dto.EmailTemplateUpdateRequest;
import com.econet.leads.service.EmailService;
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
@RequestMapping("/api/email")
@RequiredArgsConstructor
@Tag(name = "Email", description = "SMTP status and email templates")
public class EmailController {

    private final EmailService emailService;

    @GetMapping("/status")
    @Operation(summary = "SMTP status", description = "{ configured, from, fromName, rateLimitPerHour }")
    public ResponseEntity<EmailDTOs.Status> status() {
        return ResponseEntity.ok(emailService.status());
    }

    @GetMapping("/templates")
    @Operation(summary = "All email templates")
    public ResponseEntity<List<EmailDTOs.Template>> templates() {
        return ResponseEntity.ok(emailService.templates());
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Edit a template (ADMIN)", description = "{ subject?, body?, active? }")
    public ResponseEntity<EmailDTOs.Template> update(@PathVariable UUID id, @Valid @RequestBody EmailTemplateUpdateRequest request) {
        return ResponseEntity.ok(emailService.updateTemplate(id, request));
    }
}
