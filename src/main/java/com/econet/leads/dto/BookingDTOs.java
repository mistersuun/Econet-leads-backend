package com.econet.leads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Shapes of the booking / visit / availability endpoints (CRM side). */
public final class BookingDTOs {

    private BookingDTOs() {
    }

    /** POST /api/leads/{id}/booking-links */
    public record LinkResponse(String url, LocalDateTime expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Visit(UUID id, UUID leadId, String leadName, String leadPhone, String leadAddress,
                        LocalDateTime startsAt, LocalDateTime endsAt, String status, String bookedVia) {
    }

    /** PATCH /api/visits/{id} */
    public record VisitStatusRequest(@NotNull(message = "status is required") String status) {
    }

    /** One weekly window; times as HH:mm. */
    public record Window(UUID id, int weekday, String startTime, String endTime, int slotMinutes, boolean active) {
    }
}
