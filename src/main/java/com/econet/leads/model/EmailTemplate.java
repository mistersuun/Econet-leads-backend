package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Plain-text email template with {{placeholders}} (V9), one per (key, language).
 */
@Entity
@Table(name = "email_templates", uniqueConstraints = @UniqueConstraint(
        name = "uk_email_templates_key_language", columnNames = {"template_key", "language"}))
@Data
@NoArgsConstructor
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** quote_followup | visit_confirmation | missed_you | intro (column template_key: "key" is reserved in H2). */
    @Column(name = "template_key", nullable = false, length = 50)
    private String key;

    @Column(nullable = false, length = 2)
    private String language;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private Boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
