package com.econet.leads.dto;

import com.econet.leads.model.CallOutcome;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class LogCallRequest {

    @NotNull(message = "L'issue de l'appel est obligatoire")
    private CallOutcome outcome;

    @Size(max = 5000, message = "Les notes ne doit pas dépasser 5000 caractères")
    private String notes;

    @Size(max = 255, message = "La personne-ressource ne doit pas dépasser 255 caractères")
    private String contactPerson;

    /** Optional explicit follow-up (America/Montreal wall time); defaults per outcome when omitted */
    private LocalDateTime nextFollowUpAt;

    @DecimalMin(value = "0.0", message = "La valeur estimée doit être positive")
    @Digits(integer = 8, fraction = 2, message = "La valeur estimée doit avoir au plus 8 chiffres et 2 décimales")
    private BigDecimal estimatedValue;

    /** Duration of the call, when timed by the UI. */
    @Min(value = 0, message = "La durée doit être positive")
    @Max(value = 86400, message = "La durée ne doit pas dépasser 86 400 secondes")
    private Integer durationSeconds;

    /** Objections heard during the call (V14): at most 5, each at most 80 characters. */
    @Size(max = 5, message = "Au plus 5 objections")
    private List<@Size(max = 80, message = "Chaque objection doit faire au plus 80 caractères") String> objections;

    /** Partial Qualification, merged into the lead (see QualificationService). */
    private Map<String, Object> qualification;

    /** Sets nextFollowUpAt without changing the status rules; only with NO_ANSWER / VOICEMAIL / CALLBACK. */
    private LocalDateTime snoozeUntil;
}
