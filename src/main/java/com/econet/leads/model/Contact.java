package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "contacts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "contact_date", nullable = false)
    private LocalDateTime contactDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_type", nullable = false, length = 20)
    private ContactType contactType;

    @Column(name = "contact_status", nullable = false, length = 50)
    private String contactStatus;

    @Column(name = "contact_person")
    private String contactPerson;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "next_action", columnDefinition = "TEXT")
    private String nextAction;

    @Column(name = "next_action_date")
    private LocalDateTime nextActionDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ContactOutcome outcome;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum ContactType {
        APPEL,
        EMAIL,
        VISITE,
        /** Pipeline status change / free note recorded by the CRM (not a call). */
        NOTE
    }

    public enum ContactOutcome {
        // Legacy values (kept readable for existing rows)
        CONTRAT,
        REFUS,
        EN_ATTENTE,
        // CallOutcome values
        NO_ANSWER,
        VOICEMAIL,
        CALLBACK,
        INTERESTED,
        NOT_INTERESTED,
        QUOTE_SENT,
        WON,
        WRONG_NUMBER,
        DO_NOT_CALL
    }
}
