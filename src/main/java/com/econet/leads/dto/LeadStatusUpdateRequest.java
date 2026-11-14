package com.econet.leads.dto;

import com.econet.leads.model.LeadStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeadStatusUpdateRequest {

    @NotNull(message = "Le statut est obligatoire")
    private LeadStatus status;

    @Size(max = 5000, message = "La note ne doit pas dépasser 5000 caractères")
    private String note;
}
