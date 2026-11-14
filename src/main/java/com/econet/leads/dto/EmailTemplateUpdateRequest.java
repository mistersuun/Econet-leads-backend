package com.econet.leads.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of PUT /api/email/templates/{id} (fields left null are unchanged). */
@Data
public class EmailTemplateUpdateRequest {
    @Size(max = 500, message = "Le sujet ne doit pas dépasser 500 caractères")
    private String subject;
    @Size(max = 20000, message = "Le contenu ne doit pas dépasser 20 000 caractères")
    private String body;
    private Boolean active;
}
