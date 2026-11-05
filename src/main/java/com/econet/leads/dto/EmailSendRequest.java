package com.econet.leads.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** Body of POST /api/leads/{id}/emails and /emails/preview. */
@Data
public class EmailSendRequest {

    /** quote_followup | visit_confirmation | missed_you | intro */
    @NotBlank(message = "templateKey is required")
    private String templateKey;

    /** fr | en (default: the lead's qualification.language, else fr) */
    private String language;

    /** Recipient; defaults to qualification.email, then the lead's email. */
    @Size(max = 255)
    private String to;

    /** Optional estimate rendered into {{estimateLines}} / {{estimateTotal}}. */
    private PricingDTOs.EstimateInput estimate;

    /** Visit date/time (America/Montreal); an ICS invite is attached when set. */
    private LocalDateTime visitAt;

    @Size(max = 2000, message = "extraNote must not exceed 2000 characters")
    private String extraNote;
}
