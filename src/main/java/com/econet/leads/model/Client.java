package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/** A won business that is now served (V16). One row per business. */
@Entity
@Table(name = "clients")
@Data
@NoArgsConstructor
public class Client {

    public static final Set<String> FREQUENCIES = Set.of("weekly", "bi-weekly", "monthly", "one-time");
    public static final Set<String> STATUSES = Set.of("active", "paused", "ended");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Business business;

    @Column(nullable = false, length = 200)
    private String service;

    /** weekly | bi-weekly | monthly | one-time */
    @Column(nullable = false, length = 20)
    private String frequency = "one-time";

    @Column(name = "price_per_visit", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerVisit = BigDecimal.ZERO;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;

    @Column(name = "next_visit_at")
    private LocalDateTime nextVisitAt;

    /** active | paused | ended */
    @Column(nullable = false, length = 20)
    private String status = "active";

    @Column(name = "first_cleaning_done_at")
    private LocalDateTime firstCleaningDoneAt;

    @Column(name = "review_requested_at")
    private LocalDateTime reviewRequestedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
