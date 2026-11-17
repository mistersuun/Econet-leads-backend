package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A priced quote (V15) sent to a prospect, with a public accept link identified by {@link #token}. */
@Entity
@Table(name = "quotes")
@Getter
@Setter
@NoArgsConstructor
public class Quote {

    public static final String DRAFT = "draft";
    public static final String SENT = "sent";
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String EXPIRED = "expired";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    /** Q-YYYY-NNNN */
    @Column(nullable = false, unique = true, length = 20)
    private String number;

    @Column(nullable = false, length = 2)
    private String language;

    /** The estimate input as submitted: {service, area, propertyType, addOnIds, frequency}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> input;

    /** Itemised lines as shown on the PDF: [{id, label, amount}] (the discount line is negative). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> lines;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal tax;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 20)
    private String status = DRAFT;

    /** 32 random bytes, base64url (43 chars): the secret of the public link. */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "accepted_name", length = 200)
    private String acceptedName;

    /** Keyed hash (HMAC-SHA-256) of the accepting IP and quote id; the IP itself is never stored. */
    @Column(name = "accepted_ip_hash", length = 64)
    private String acceptedIpHash;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;
}
