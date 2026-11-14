package com.econet.leads.dto;

import com.econet.leads.model.TenderStatus;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** PATCH /api/tenders/{id}: both fields optional; notes "" clears the notes. */
@Data
public class TenderUpdateRequest {

    private TenderStatus status;

    @Size(max = 10000, message = "Les notes ne doit pas dépasser 10000 caractères")
    private String notes;
}
