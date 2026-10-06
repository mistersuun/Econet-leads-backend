package com.econet.leads.dto;

import com.econet.leads.model.LeadStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeadStatusUpdateRequest {

    @NotNull(message = "status is required")
    private LeadStatus status;

    @Size(max = 5000, message = "note must not exceed 5000 characters")
    private String note;
}
