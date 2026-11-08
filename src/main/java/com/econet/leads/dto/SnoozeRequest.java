package com.econet.leads.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** Body of POST /api/leads/{id}/snooze. */
@Data
public class SnoozeRequest {

    /** New follow-up (America/Montreal wall time). */
    @NotNull(message = "until is required")
    private LocalDateTime until;

    @Size(max = 1000, message = "reason must not exceed 1000 characters")
    private String reason;
}
