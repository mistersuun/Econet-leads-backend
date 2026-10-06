package com.econet.leads.repository;

import com.econet.leads.model.ScraperJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScraperJobRepository extends JpaRepository<ScraperJob, UUID> {

    // Real paged query with the source eagerly fetched (sorting comes from the Pageable)
    @Query(value = "SELECT j FROM ScraperJob j LEFT JOIN FETCH j.source",
           countQuery = "SELECT COUNT(j) FROM ScraperJob j")
    Page<ScraperJob> findAllWithSource(Pageable pageable);

    boolean existsBySourceIdAndStatusIn(UUID sourceId, java.util.Collection<ScraperJob.JobStatus> statuses);

    long countByCreatedAtGreaterThanEqual(LocalDateTime since);

    // Totals over completed jobs: [processed, added, updated]
    @Query("SELECT COALESCE(SUM(j.recordsProcessed), 0), COALESCE(SUM(j.recordsAdded), 0), COALESCE(SUM(j.recordsUpdated), 0) "
         + "FROM ScraperJob j WHERE j.status = com.econet.leads.model.ScraperJob.JobStatus.COMPLETED")
    List<Object[]> sumCompletedRecordCounts();

    // Find by ID with source eagerly fetched
    @Query("SELECT j FROM ScraperJob j LEFT JOIN FETCH j.source WHERE j.id = :id")
    java.util.Optional<ScraperJob> findByIdWithSource(@Param("id") UUID id);

    // Find jobs by source
    @Query("SELECT j FROM ScraperJob j LEFT JOIN FETCH j.source WHERE j.source.id = :sourceId ORDER BY j.createdAt DESC")
    Page<ScraperJob> findBySourceIdOrderByCreatedAtDesc(@Param("sourceId") UUID sourceId, Pageable pageable);

    // Find jobs by status
    @Query("SELECT j FROM ScraperJob j LEFT JOIN FETCH j.source WHERE j.status = :status")
    List<ScraperJob> findByStatus(@Param("status") ScraperJob.JobStatus status);

    // Find running jobs
    @Query("SELECT j FROM ScraperJob j LEFT JOIN FETCH j.source WHERE j.status = 'RUNNING'")
    List<ScraperJob> findRunningJobs();

    // Find recent jobs
    @Query("SELECT j FROM ScraperJob j LEFT JOIN FETCH j.source WHERE j.createdAt >= :since ORDER BY j.createdAt DESC")
    List<ScraperJob> findRecentJobs(@Param("since") LocalDateTime since);

    // Count jobs by status
    Long countByStatus(ScraperJob.JobStatus status);

    // Find last successful job for a source
    @Query("SELECT j FROM ScraperJob j WHERE j.source.id = :sourceId AND j.status = 'COMPLETED' ORDER BY j.completedAt DESC")
    List<ScraperJob> findLastSuccessfulJobForSource(@Param("sourceId") UUID sourceId, Pageable pageable);
}
