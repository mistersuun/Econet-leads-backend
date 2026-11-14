package com.econet.leads.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of PATCH /api/businesses/{id}/phone. Format is validated by LeadService (10 digits NANP, optional +1). */
@Data
public class PhoneUpdateRequest {

    @NotBlank(message = "Le téléphone est obligatoire")
    @Size(max = 30, message = "Le téléphone ne doit pas dépasser 30 caractères")
    private String phone;
}
