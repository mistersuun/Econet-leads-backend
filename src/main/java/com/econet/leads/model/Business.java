package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "businesses")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_type", nullable = false, length = 100)
    private String businessType;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private BusinessCategory category;

    @Column(name = "address_street")
    private String addressStreet;

    @Column(name = "address_city", length = 100)
    private String addressCity;

    @Column(name = "address_province", length = 50)
    private String addressProvince = "QC";

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(length = 20)
    private String phone;

    @Column(name = "phone_normalized", length = 15)
    private String phoneNormalized;

    private String email;

    @Column(length = 500)
    private String website;

    @Column(precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "data_source", nullable = false, length = 100)
    private String dataSource;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "external_id")
    private String externalId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_verified")
    private LocalDateTime lastVerified;

    @Column(name = "data_quality_score")
    private Integer dataQualityScore = 0;

    // --- Calling CRM / pipeline fields (V6) ---

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_status", nullable = false, length = 30)
    private LeadStatus leadStatus = LeadStatus.NEW;

    // EAGER on purpose: every BusinessDTO exposes assignedToName and there are only a handful of
    // users, so Hibernate resolves them from the persistence context with very few extra selects.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assigned_to")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User assignedTo;

    @Column(name = "last_contacted_at")
    private LocalDateTime lastContactedAt;

    @Column(name = "next_follow_up_at")
    private LocalDateTime nextFollowUpAt;

    @Column(name = "contact_count", nullable = false)
    private Integer contactCount = 0;

    @Column(name = "estimated_value", precision = 10, scale = 2)
    private BigDecimal estimatedValue;

    /**
     * Short source-specific facts shown to the caller (V8), e.g. {"NEQ": "...", "Secteur": "..."}.
     * PostgreSQL JSONB does not keep key order; clients should not rely on it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_details", columnDefinition = "jsonb")
    private Map<String, String> sourceDetails;

    // Helper method to get full address
    public String getFullAddress() {
        StringBuilder sb = new StringBuilder();
        if (addressStreet != null) sb.append(addressStreet);
        if (addressCity != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(addressCity);
        }
        if (addressProvince != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(addressProvince);
        }
        if (postalCode != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(postalCode);
        }
        return sb.toString();
    }
}
