package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.service.BusinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Executes one import job (normally on the import executor thread).
 *
 * Deliberately NOT transactional: each record is persisted through
 * {@link BusinessService#importRecord(Business)} in its own REQUIRES_NEW transaction, and job
 * status/progress through {@link ImportJobTracker}, so a failing record is counted as an error and
 * the import continues.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobRunner {

    static final int PROGRESS_EVERY = 50;
    static final int MAX_ERROR_LINES = 100;

    private final BusinessService businessService;
    private final ImportJobTracker jobTracker;

    public void run(UUID jobId, UUID dataSourceId, String sourceName, BusinessRecordFetcher fetcher) {
        try {
            jobTracker.markRunning(jobId);
            log.info("Import job {} started for {}", jobId, sourceName);

            List<Business> records = fetcher.fetch();
            log.info("Import job {}: processing {} records from {}", jobId, records.size(), sourceName);

            int processed = 0;
            int added = 0;
            int updated = 0;
            int failed = 0;
            StringBuilder errors = new StringBuilder();

            for (Business record : records) {
                processed++;
                try {
                    BusinessService.UpsertResult result = businessService.importRecord(record);
                    if (result.created()) {
                        added++;
                    } else {
                        updated++;
                    }
                } catch (Exception e) {
                    failed++;
                    String message = rootMessage(e);
                    log.warn("Import job {}: record {} ({}) failed: {}", jobId, processed, record.getBusinessName(), message);
                    if (failed <= MAX_ERROR_LINES) {
                        errors.append(String.format("Record %d (%s): %s%n", processed, record.getBusinessName(), message));
                    }
                }

                if (processed % PROGRESS_EVERY == 0) {
                    log.info("Import job {}: {}/{} records", jobId, processed, records.size());
                    if (!jobTracker.updateProgress(jobId, processed, added, updated)) {
                        log.info("Import job {} was cancelled; stopping after {} records", jobId, processed);
                        return;
                    }
                }
            }

            if (failed > MAX_ERROR_LINES) {
                errors.append(String.format("... and %d more failed records%n", failed - MAX_ERROR_LINES));
            }

            jobTracker.complete(jobId, processed, added, updated, errors.toString());
            jobTracker.updateDataSourceSync(dataSourceId, added + updated);
            log.info("Import job {} completed for {}: {} processed, {} added, {} updated, {} failed",
                    jobId, sourceName, processed, added, updated, failed);
        } catch (Throwable e) {
            log.error("Import job {} for {} failed: {}", jobId, sourceName, e.getMessage(), e);
            try {
                jobTracker.fail(jobId, rootMessage(e));
            } catch (Exception inner) {
                log.error("Could not mark import job {} as FAILED", jobId, inner);
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (t != e && e.getMessage() != null && !e.getMessage().equals(msg)) {
            msg = e.getClass().getSimpleName() + ": " + msg;
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
