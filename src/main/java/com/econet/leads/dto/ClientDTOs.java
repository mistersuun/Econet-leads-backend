package com.econet.leads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Shapes of the clients API (V16). */
public final class ClientDTOs {

    private ClientDTOs() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ClientDTO(UUID id, UUID businessId, String businessName, String city, String phone, String email,
                            String service, String frequency, BigDecimal pricePerVisit,
                            /** price x visits per month; 0 for one-time */
                            BigDecimal monthlyValue,
                            LocalDate startDate, LocalDate contractEndDate, LocalDateTime nextVisitAt, String status,
                            LocalDateTime firstCleaningDoneAt, LocalDateTime reviewRequestedAt, String notes,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** GET /api/dashboard/clients */
    public record ClientsSummary(long active, BigDecimal monthlyRecurringRevenue, long renewalsNext30Days,
                                 long reviewsPending) {
    }

    @Data
    public static class ReviewRequest {
        /** email | link */
        @NotBlank(message = "channel is required (email or link)")
        private String channel;
    }

    public record ReviewResult(String channel, String url, String message, LocalDateTime requestedAt) {
    }

    /** GET /api/clients/review-config: lets the UI explain what is missing before the caller tries. */
    public record ReviewConfig(boolean googleReviewUrl, boolean smtp) {
    }
}
