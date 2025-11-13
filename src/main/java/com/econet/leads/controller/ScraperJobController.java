package com.econet.leads.controller;

import com.econet.leads.dto.ScraperJobDTO;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.ScraperJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for monitoring scraper jobs
 */
@RestController
@RequestMapping("/api/scraper-jobs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ScraperJobController {

    private final ScraperJobRepository scraperJobRepository;

    /**
     * Get all scraper jobs with pagination
     */
    @GetMapping
    public ResponseEntity<Page<ScraperJobDTO>> getAllJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        Sort.Direction direction = sortDirection.equalsIgnoreCase("ASC")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        // Fetch all jobs with source eagerly loaded
        List<ScraperJob> allJobs = scraperJobRepository.findAllWithSource();

        // Convert to DTOs
        List<ScraperJobDTO> dtos = allJobs.stream()
                .map(ScraperJobDTO::fromEntity)
                .collect(Collectors.toList());

        // Manual pagination
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), dtos.size());
        List<ScraperJobDTO> pageContent = dtos.subList(start, end);

        Page<ScraperJobDTO> result = new PageImpl<>(pageContent, pageable, dtos.size());

        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific job by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ScraperJobDTO> getJobById(@PathVariable UUID id) {
        return scraperJobRepository.findByIdWithSource(id)
                .map(ScraperJobDTO::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get jobs by data source
     */
    @GetMapping("/source/{sourceId}")
    public ResponseEntity<Page<ScraperJobDTO>> getJobsBySource(
            @PathVariable UUID sourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<ScraperJob> jobs = scraperJobRepository.findBySourceIdOrderByCreatedAtDesc(sourceId, pageable);

        // Convert to DTOs
        Page<ScraperJobDTO> dtoPage = jobs.map(ScraperJobDTO::fromEntity);

        return ResponseEntity.ok(dtoPage);
    }

    /**
     * Get all running jobs
     */
    @GetMapping("/running")
    public ResponseEntity<List<ScraperJobDTO>> getRunningJobs() {
        List<ScraperJob> runningJobs = scraperJobRepository.findRunningJobs();
        List<ScraperJobDTO> dtos = runningJobs.stream()
                .map(ScraperJobDTO::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get jobs by status
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<ScraperJobDTO>> getJobsByStatus(@PathVariable String status) {
        try {
            ScraperJob.JobStatus jobStatus = ScraperJob.JobStatus.valueOf(status.toUpperCase());
            List<ScraperJob> jobs = scraperJobRepository.findByStatus(jobStatus);
            List<ScraperJobDTO> dtos = jobs.stream()
                    .map(ScraperJobDTO::fromEntity)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get recent jobs (last 24 hours by default)
     */
    @GetMapping("/recent")
    public ResponseEntity<List<ScraperJobDTO>> getRecentJobs(
            @RequestParam(defaultValue = "24") int hours) {

        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        List<ScraperJob> recentJobs = scraperJobRepository.findRecentJobs(since);
        List<ScraperJobDTO> dtos = recentJobs.stream()
                .map(ScraperJobDTO::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    /**
     * Get job statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getJobStatistics() {
        Map<String, Object> stats = new HashMap<>();

        // Count by status
        stats.put("pending", scraperJobRepository.countByStatus(ScraperJob.JobStatus.PENDING));
        stats.put("running", scraperJobRepository.countByStatus(ScraperJob.JobStatus.RUNNING));
        stats.put("completed", scraperJobRepository.countByStatus(ScraperJob.JobStatus.COMPLETED));
        stats.put("failed", scraperJobRepository.countByStatus(ScraperJob.JobStatus.FAILED));
        stats.put("cancelled", scraperJobRepository.countByStatus(ScraperJob.JobStatus.CANCELLED));

        // Total jobs
        stats.put("total", scraperJobRepository.count());

        // Recent jobs (last 24 hours)
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        List<ScraperJob> recent24h = scraperJobRepository.findRecentJobs(since24h);
        stats.put("last24Hours", recent24h.size());

        // Calculate total records processed
        List<ScraperJob> completedJobs = scraperJobRepository.findByStatus(ScraperJob.JobStatus.COMPLETED);
        int totalRecordsProcessed = completedJobs.stream()
                .mapToInt(job -> job.getRecordsProcessed() != null ? job.getRecordsProcessed() : 0)
                .sum();
        int totalRecordsAdded = completedJobs.stream()
                .mapToInt(job -> job.getRecordsAdded() != null ? job.getRecordsAdded() : 0)
                .sum();
        int totalRecordsUpdated = completedJobs.stream()
                .mapToInt(job -> job.getRecordsUpdated() != null ? job.getRecordsUpdated() : 0)
                .sum();

        stats.put("totalRecordsProcessed", totalRecordsProcessed);
        stats.put("totalRecordsAdded", totalRecordsAdded);
        stats.put("totalRecordsUpdated", totalRecordsUpdated);

        return ResponseEntity.ok(stats);
    }

    /**
     * Get last successful job for a data source
     */
    @GetMapping("/source/{sourceId}/last-successful")
    public ResponseEntity<ScraperJobDTO> getLastSuccessfulJob(@PathVariable UUID sourceId) {
        Pageable pageable = PageRequest.of(0, 1);
        List<ScraperJob> jobs = scraperJobRepository.findLastSuccessfulJobForSource(sourceId, pageable);

        if (jobs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ScraperJobDTO dto = ScraperJobDTO.fromEntity(jobs.get(0));
        return ResponseEntity.ok(dto);
    }

    /**
     * Cancel a running job (mark as cancelled)
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable UUID id) {
        ScraperJob job = scraperJobRepository.findByIdWithSource(id).orElse(null);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        if (job.getStatus() != ScraperJob.JobStatus.RUNNING
                && job.getStatus() != ScraperJob.JobStatus.PENDING) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only running or pending jobs can be cancelled"));
        }

        job.setStatus(ScraperJob.JobStatus.CANCELLED);
        job.setCompletedAt(LocalDateTime.now());
        job.setErrors("Cancelled by user");

        ScraperJob savedJob = scraperJobRepository.save(job);
        ScraperJobDTO dto = ScraperJobDTO.fromEntity(savedJob);

        return ResponseEntity.ok(dto);
    }

    /**
     * Delete a job (only completed, failed, or cancelled jobs)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteJob(@PathVariable UUID id) {
        ScraperJob job = scraperJobRepository.findById(id).orElse(null);

        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        if (job.getStatus() == ScraperJob.JobStatus.RUNNING
                || job.getStatus() == ScraperJob.JobStatus.PENDING) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot delete running or pending jobs"));
        }

        scraperJobRepository.delete(job);

        return ResponseEntity.ok(Map.of("message", "Job deleted successfully"));
    }
}
