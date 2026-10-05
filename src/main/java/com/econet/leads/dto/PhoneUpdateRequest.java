package com.econet.leads.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of PATCH /api/businesses/{id}/phone. Format is validated by LeadService (10 digits NANP, optional +1). */
@Data
public class PhoneUpdateRequest {

    @NotBlank(message = "phone is required")
    @Size(max = 30, message = "phone must not exceed 30 characters")
    private String phone;
}
