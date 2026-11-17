package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A discovery search (city + categories) re-run on a schedule; see SavedSearchService (V13). */
@Entity
@Table(name = "saved_searches")
@Getter
@Setter
@NoArgsConstructor
public class SavedSearch {

    public static final String MONTHLY = "monthly";
    public static final String WEEKLY = "weekly";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 100)
    private String city;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories", columnDefinition = "jsonb", nullable = false)
    private List<String> categories = new ArrayList<>();

    @Column(name = "max_results", nullable = false)
    private int maxResults = 100;

    @Column(nullable = false, length = 20)
    private String frequency = MONTHLY;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "assign_to_user_id")
    private User assignTo;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_added")
    private Integer lastAdded;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
