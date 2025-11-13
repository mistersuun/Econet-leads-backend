package com.econet.leads.dto;

import com.econet.leads.model.Contact;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ContactDTO {
    private UUID id;
    private UUID businessId;
    private String businessName;
    private LocalDateTime contactDate;
    private Contact.ContactType contactType;
    private String contactStatus;
    private String contactPerson;
    private String notes;
    private String nextAction;
    private LocalDateTime nextActionDate;
    private UUID userId;
    private String username;
    private Contact.ContactOutcome outcome;
    private LocalDateTime createdAt;
}
