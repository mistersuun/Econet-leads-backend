package com.econet.leads.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "scraper_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScraperJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private DataSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 50)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private JobStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "records_processed")
    private Integer recordsProcessed = 0;

    @Column(name = "records_added")
    private Integer recordsAdded = 0;

    @Column(name = "records_updated")
    private Integer recordsUpdated = 0;

    @Column(columnDefinition = "TEXT")
    private String errors;

    @Column(columnDefinition = "TEXT")
    private String log;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum JobType {
        FULL_SYNC,
        INCREMENTAL_SYNC,
        MANUAL_SCRAPE
    }

    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // Helper method to check if job is terminal
    public boolean isTerminal() {
        return status == JobStatus.COMPLETED ||
               status == JobStatus.FAILED ||
               status == JobStatus.CANCELLED;
    }

    // Helper method to calculate duration
    public Long getDurationSeconds() {
        if (startedAt != null && completedAt != null) {
            return java.time.Duration.between(startedAt, completedAt).getSeconds();
        }
        return null;
    }
}
