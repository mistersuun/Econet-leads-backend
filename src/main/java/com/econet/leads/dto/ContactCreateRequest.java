package com.econet.leads.dto;

import com.econet.leads.model.Contact;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ContactCreateRequest {
    @NotNull(message = "L'identifiant de l'entreprise est obligatoire")
    private UUID businessId;

    private LocalDateTime contactDate;

    @NotNull(message = "Le type de contact est obligatoire")
    private Contact.ContactType contactType;

    @NotBlank(message = "Le statut du contact est obligatoire")
    private String contactStatus;

    private String contactPerson;
    private String notes;
    private String nextAction;
    private LocalDateTime nextActionDate;
    private Contact.ContactOutcome outcome;
}
