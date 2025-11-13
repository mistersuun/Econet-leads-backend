package com.econet.leads.dto;

import com.econet.leads.model.Contact;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ContactCreateRequest {
    @NotNull(message = "Business ID is required")
    private UUID businessId;

    private LocalDateTime contactDate;

    @NotNull(message = "Contact type is required")
    private Contact.ContactType contactType;

    @NotBlank(message = "Contact status is required")
    private String contactStatus;

    private String contactPerson;
    private String notes;
    private String nextAction;
    private LocalDateTime nextActionDate;
    private Contact.ContactOutcome outcome;
}
