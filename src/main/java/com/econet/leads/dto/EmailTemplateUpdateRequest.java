package com.econet.leads.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of PUT /api/email/templates/{id} (fields left null are unchanged). */
@Data
public class EmailTemplateUpdateRequest {
    @Size(max = 500)
    private String subject;
    @Size(max = 20000)
    private String body;
    private Boolean active;
}
