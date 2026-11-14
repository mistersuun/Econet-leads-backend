package com.econet.leads.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** Body of POST /api/leads/{id}/snooze. */
@Data
public class SnoozeRequest {

    /** New follow-up (America/Montreal wall time). */
    @NotNull(message = "La date de fin est obligatoire")
    private LocalDateTime until;

    @Size(max = 1000, message = "La raison ne doit pas dépasser 1000 caractères")
    private String reason;
}
