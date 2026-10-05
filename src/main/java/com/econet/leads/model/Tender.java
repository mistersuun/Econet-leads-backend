package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A public tender notice (appel d'offres) or awarded contract matching our cleaning keywords.
 * Imports upsert by (source, externalId) and never touch {@link #status} / {@link #notes}.
 */
@Entity
@Table(name = "tenders", uniqueConstraints = @UniqueConstraint(
        name = "uk_tenders_source_external_id", columnNames = {"source", "external_id"}))
@Data
@NoArgsConstructor
public class Tender {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenderSource source;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 500)
    private String buyer;

    @Column(length = 500)
    private String region;

    @Column(length = 255)
    private String category;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "closing_at")
    private LocalDateTime closingAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "estimated_value", precision = 15, scale = 2)
    private BigDecimal estimatedValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_keywords", columnDefinition = "jsonb")
    private List<String> matchedKeywords = new ArrayList<>();

    /** TENDER (call for tenders) or AWARD (awarded contract, SEAO). */
    @Column(name = "notice_type", length = 20)
    private String noticeType;

    /** Supplier(s) of an awarded contract. */
    @Column(name = "awarded_to", length = 500)
    private String awardedTo;

    /** End of the awarded contract period (renewal opportunity). */
    @Column(name = "contract_end_at")
    private LocalDateTime contractEndAt;

    /** Date of the source release/row the current values come from (older releases never overwrite newer ones). */
    @Column(name = "source_release_at")
    private LocalDateTime sourceReleaseAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenderStatus status = TenderStatus.NEW;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
