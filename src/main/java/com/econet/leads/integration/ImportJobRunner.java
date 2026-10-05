package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.Tender;
import com.econet.leads.service.BusinessService;
import com.econet.leads.service.TenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Executes one import job (normally on the import executor thread).
 *
 * Deliberately NOT transactional: each record is persisted through
 * {@link BusinessService#importRecord(Business)} / {@link TenderService#importTender(Tender)} in its
 * own REQUIRES_NEW transaction, and job status/progress through {@link ImportJobTracker}, so a
 * failing record is counted as an error and the import continues.
 *
 * Records are consumed from a {@link RecordStream} one at a time; the runner itself keeps only
 * counters, never the records.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobRunner {

    static final int PROGRESS_EVERY = 50;
    static final int MAX_ERROR_LINES = 100;

    private final BusinessService businessService;
    private final TenderService tenderService;
    private final ImportJobTracker jobTracker;

    /** Legacy list-based fetchers (CKAN sources, dedicated services). */
    public void run(UUID jobId, UUID dataSourceId, String sourceName, BusinessRecordFetcher fetcher) {
        runBusinesses(jobId, dataSourceId, sourceName, sink -> {
            for (Business b : fetcher.fetch()) {
                if (!sink.test(b)) {
                    return;
                }
            }
        }, new ImportReport());
    }

    public void runBusinesses(UUID jobId, UUID dataSourceId, String sourceName, RecordStream<Business> stream, ImportReport report) {
        runGeneric(jobId, dataSourceId, sourceName, stream, report,
                b -> businessService.importRecord(b).created(), Business::getBusinessName);
    }

    public void runTenders(UUID jobId, UUID dataSourceId, String sourceName, RecordStream<Tender> stream, ImportReport report) {
        runGeneric(jobId, dataSourceId, sourceName, stream, report,
                tenderService::importTender, t -> t.getExternalId() + " " + t.getTitle());
    }

    private <T> void runGeneric(UUID jobId, UUID dataSourceId, String sourceName, RecordStream<T> stream,
                                ImportReport report, Function<T, Boolean> upsert, Function<T, String> label) {
        try {
            jobTracker.markRunning(jobId);
            log.info("Import job {} started for {}", jobId, sourceName);

            Counters c = new Counters();
            Predicate<T> sink = record -> {
                c.processed++;
                try {
                    if (upsert.apply(record)) {
                        c.added++;
                    } else {
                        c.updated++;
                    }
                } catch (Exception e) {
                    c.failed++;
                    String message = rootMessage(e);
                    String name = safeLabel(label, record);
                    log.warn("Import job {}: record {} ({}) failed: {}", jobId, c.processed, name, message);
                    if (c.failed <= MAX_ERROR_LINES) {
                        c.errors.append(String.format("Record %d (%s): %s%n", c.processed, name, message));
                    }
                }
                if (c.processed % PROGRESS_EVERY == 0) {
                    log.info("Import job {}: {} records", jobId, c.processed);
                    if (!jobTracker.updateProgress(jobId, c.processed, c.added, c.updated)) {
                        log.info("Import job {} was cancelled; stopping after {} records", jobId, c.processed);
                        c.cancelled = true;
                        return false;
                    }
                }
                return true;
            };

            stream.forEach(sink);
            if (c.cancelled) {
                return;
            }

            if (c.failed > MAX_ERROR_LINES) {
                c.errors.append(String.format("... and %d more failed records%n", c.failed - MAX_ERROR_LINES));
            }

            jobTracker.complete(jobId, c.processed, c.added, c.updated, c.errors.toString(), report.asText());
            jobTracker.updateDataSourceSync(dataSourceId, c.added + c.updated);
            log.info("Import job {} completed for {}: {} processed, {} added, {} updated, {} failed",
                    jobId, sourceName, c.processed, c.added, c.updated, c.failed);
        } catch (Throwable e) {
            log.error("Import job {} for {} failed: {}", jobId, sourceName, e.getMessage(), e);
            try {
                jobTracker.fail(jobId, rootMessage(e), report.asText());
            } catch (Exception inner) {
                log.error("Could not mark import job {} as FAILED", jobId, inner);
            }
        }
    }

    private static <T> String safeLabel(Function<T, String> label, T record) {
        try {
            return label.apply(record);
        } catch (Exception e) {
            return "?";
        }
    }

    private static final class Counters {
        int processed;
        int added;
        int updated;
        int failed;
        boolean cancelled;
        final StringBuilder errors = new StringBuilder();
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (t != e && e.getMessage() != null && !e.getMessage().equals(msg)) {
            msg = e.getMessage() + " (" + t.getClass().getSimpleName() + ": " + msg + ")";
        }
        return msg.length() > 2000 ? msg.substring(0, 2000) + "..." : msg;
    }
}
