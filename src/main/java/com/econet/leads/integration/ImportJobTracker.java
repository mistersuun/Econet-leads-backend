package com.econet.leads.integration;

import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.ScraperJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Job bookkeeping for imports. Every method is public and runs in its own transaction
 * (REQUIRES_NEW) so status and progress updates commit immediately and independently of the
 * per-record transactions. (The previous code put @Transactional on private methods, which Spring's
 * proxies silently ignore.)
 */
@Component
@RequiredArgsConstructor
public class ImportJobTracker {

    private final ScraperJobRepository scraperJobRepository;
    private final DataSourceRepository dataSourceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScraperJob createPendingJob(DataSource dataSource) {
        ScraperJob job = new ScraperJob();
        job.setSource(dataSource);
        job.setJobType(ScraperJob.JobType.FULL_SYNC);
        job.setStatus(ScraperJob.JobStatus.PENDING);
        return scraperJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID jobId) {
        ScraperJob job = load(jobId);
        job.setStatus(ScraperJob.JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        scraperJobRepository.save(job);
    }

    /**
     * Saves progress counters.
     *
     * @return false if the job was cancelled in the meantime (the runner should stop)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean updateProgress(UUID jobId, int processed, int added, int updated) {
        ScraperJob job = load(jobId);
        if (job.getStatus() == ScraperJob.JobStatus.CANCELLED) {
            return false;
        }
        job.setRecordsProcessed(processed);
        job.setRecordsAdded(added);
        job.setRecordsUpdated(updated);
        scraperJobRepository.save(job);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, int processed, int added, int updated, String errors) {
        complete(jobId, processed, added, updated, errors, null);
    }

    /** @param log non-fatal importer notes (row counts, skipped filters...), stored in scraper_jobs.log */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, int processed, int added, int updated, String errors, String log) {
        ScraperJob job = load(jobId);
        if (job.getStatus() == ScraperJob.JobStatus.CANCELLED) {
            return;
        }
        job.setStatus(ScraperJob.JobStatus.COMPLETED);
        job.setCompletedAt(LocalDateTime.now());
        job.setRecordsProcessed(processed);
        job.setRecordsAdded(added);
        job.setRecordsUpdated(updated);
        job.setErrors(errors == null || errors.isEmpty() ? null : errors);
        job.setLog(log);
        scraperJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String error) {
        fail(jobId, error, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, String error, String log) {
        ScraperJob job = load(jobId);
        if (log != null) {
            job.setLog(log);
        }
        job.setStatus(ScraperJob.JobStatus.FAILED);
        if (job.getStartedAt() == null) {
            job.setStartedAt(LocalDateTime.now());
        }
        job.setCompletedAt(LocalDateTime.now());
        job.setErrors(error);
        scraperJobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateDataSourceSync(UUID dataSourceId, int recordsCount) {
        dataSourceRepository.findById(dataSourceId).ifPresent(ds -> {
            ds.setLastSync(LocalDateTime.now());
            ds.setRecordsCount(recordsCount);
            dataSourceRepository.save(ds);
        });
    }

    /**
     * Marks jobs left PENDING/RUNNING by a previous process (crash/redeploy) as FAILED, so they
     * don't block new imports of the same source forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failOrphanedJobs() {
        int count = 0;
        for (ScraperJob.JobStatus status : new ScraperJob.JobStatus[]{ScraperJob.JobStatus.PENDING, ScraperJob.JobStatus.RUNNING}) {
            for (ScraperJob job : scraperJobRepository.findByStatus(status)) {
                job.setStatus(ScraperJob.JobStatus.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrors("Interrupted: the application restarted while this job was " + status);
                scraperJobRepository.save(job);
                count++;
            }
        }
        return count;
    }

    private ScraperJob load(UUID jobId) {
        return scraperJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Scraper job not found: " + jobId));
    }
}
