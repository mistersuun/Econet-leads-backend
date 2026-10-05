package com.econet.leads.dto;

import com.econet.leads.model.ScraperJob;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for ScraperJob entity to avoid LazyInitializationException
 * Flattens the source association into a nested DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScraperJobDTO {

    private UUID id;
    // Flat copies of source.id / source.sourceName for convenience in clients
    private UUID sourceId;
    private String sourceName;
    private DataSourceSummaryDTO source;
    private ScraperJob.JobType jobType;
    private ScraperJob.JobStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer recordsProcessed;
    private Integer recordsAdded;
    private Integer recordsUpdated;
    private String errors;
    private String log;
    private LocalDateTime createdAt;
    private Long durationSeconds;

    /**
     * Nested DTO for DataSource to avoid lazy loading issues
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataSourceSummaryDTO {
        private UUID id;
        private String sourceName;
        private String sourceType;
        private String sourceUrl;
    }

    /**
     * Convert ScraperJob entity to DTO
     */
    public static ScraperJobDTO fromEntity(ScraperJob job) {
        if (job == null) {
            return null;
        }

        ScraperJobDTO dto = new ScraperJobDTO();
        dto.setId(job.getId());
        dto.setJobType(job.getJobType());
        dto.setStatus(job.getStatus());
        dto.setStartedAt(job.getStartedAt());
        dto.setCompletedAt(job.getCompletedAt());
        dto.setRecordsProcessed(job.getRecordsProcessed());
        dto.setRecordsAdded(job.getRecordsAdded());
        dto.setRecordsUpdated(job.getRecordsUpdated());
        dto.setErrors(job.getErrors());
        dto.setLog(job.getLog());
        dto.setCreatedAt(job.getCreatedAt());
        dto.setDurationSeconds(job.getDurationSeconds());

        // Flatten the source association
        if (job.getSource() != null) {
            DataSourceSummaryDTO sourceDTO = new DataSourceSummaryDTO();
            sourceDTO.setId(job.getSource().getId());
            sourceDTO.setSourceName(job.getSource().getSourceName());
            sourceDTO.setSourceType(job.getSource().getSourceType() != null ? job.getSource().getSourceType().toString() : null);
            sourceDTO.setSourceUrl(job.getSource().getSourceUrl());
            dto.setSource(sourceDTO);
            dto.setSourceId(sourceDTO.getId());
            dto.setSourceName(sourceDTO.getSourceName());
        }

        return dto;
    }
}
