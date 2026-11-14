package com.econet.leads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shapes of the quote endpoints (CRM side). */
public final class QuoteDTOs {

    private QuoteDTOs() {
    }

    /** POST /api/leads/{id}/quotes */
    public record CreateRequest(@NotNull(message = "estimate is required") PricingDTOs.EstimateInput estimate,
                                String language) {
    }

    /** POST /api/quotes/{id}/send; {@code to} defaults to the lead's qualification email, then its email. */
    public record SendRequest(@Size(max = 255) String to) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Quote(UUID id, UUID leadId, String number, String language, Map<String, Object> input,
                        List<Map<String, Object>> lines, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                        String status, LocalDate validUntil, String publicUrl, String pdfUrl,
                        String createdBy, LocalDateTime createdAt, LocalDateTime sentAt,
                        LocalDateTime acceptedAt, String acceptedName, String declineReason) {
    }
}
