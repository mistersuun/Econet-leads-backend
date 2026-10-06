package com.econet.leads.dto;

import com.econet.leads.model.CallOutcome;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class LogCallRequest {

    @NotNull(message = "outcome is required")
    private CallOutcome outcome;

    @Size(max = 5000, message = "notes must not exceed 5000 characters")
    private String notes;

    @Size(max = 255, message = "contactPerson must not exceed 255 characters")
    private String contactPerson;

    /** Optional explicit follow-up (America/Montreal wall time); defaults per outcome when omitted */
    private LocalDateTime nextFollowUpAt;

    @DecimalMin(value = "0.0", message = "estimatedValue must be positive")
    @Digits(integer = 8, fraction = 2, message = "estimatedValue must have at most 8 digits and 2 decimals")
    private BigDecimal estimatedValue;
}
