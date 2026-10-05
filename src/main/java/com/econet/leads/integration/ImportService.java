package com.econet.leads.integration;

import com.econet.leads.config.AsyncConfig;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.ScraperJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Starts imports in the background.
 *
 * {@link #startImport(DataSource)} validates the source, refuses to start a second import of a
 * source that already has a PENDING/RUNNING job, persists a PENDING ScraperJob and hands the work to
 * the bounded import executor. The HTTP request returns immediately with that job; clients poll
 * {@code GET /api/scraper-jobs/{id}} to follow RUNNING -> COMPLETED/FAILED.
 */
@Service
@Slf4j
public class ImportService {

    static final List<ScraperJob.JobStatus> ACTIVE_STATUSES =
            List.of(ScraperJob.JobStatus.PENDING, ScraperJob.JobStatus.RUNNING);

    private final ScraperJobRepository scraperJobRepository;
    private final ImportJobTracker jobTracker;
    private final ImportJobRunner jobRunner;
    private final TaskExecutor importExecutor;
    private final DonneesQuebecCpeService cpeService;
    private final DonneesQuebecChsldService chsldService;
    private final DonneesMontrealRestaurantService montrealRestaurantService;
    private final StatCanHealthcareFacilitiesService statCanHealthcareService;
    private final PagesJaunesScraperService pagesJaunesScraperService;
    private final GenericCkanImportService genericCkanImportService;

    // Serializes the "is one already running? -> create job" check within this instance.
    private final Object startLock = new Object();

    public ImportService(ScraperJobRepository scraperJobRepository,
                         ImportJobTracker jobTracker,
                         ImportJobRunner jobRunner,
                         @Qualifier(AsyncConfig.IMPORT_EXECUTOR) TaskExecutor importExecutor,
                         DonneesQuebecCpeService cpeService,
                         DonneesQuebecChsldService chsldService,
                         DonneesMontrealRestaurantService montrealRestaurantService,
                         StatCanHealthcareFacilitiesService statCanHealthcareService,
                         PagesJaunesScraperService pagesJaunesScraperService,
                         GenericCkanImportService genericCkanImportService) {
        this.scraperJobRepository = scraperJobRepository;
        this.jobTracker = jobTracker;
        this.jobRunner = jobRunner;
        this.importExecutor = importExecutor;
        this.cpeService = cpeService;
        this.chsldService = chsldService;
        this.montrealRestaurantService = montrealRestaurantService;
        this.statCanHealthcareService = statCanHealthcareService;
        this.pagesJaunesScraperService = pagesJaunesScraperService;
        this.genericCkanImportService = genericCkanImportService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failOrphanedJobsOnStartup() {
        int count = jobTracker.failOrphanedJobs();
        if (count > 0) {
            log.warn("Marked {} import job(s) left PENDING/RUNNING by a previous run as FAILED", count);
        }
    }

    /**
     * @throws IllegalArgumentException       if the source type is not supported
     * @throws ImportAlreadyRunningException  if an import of this source is PENDING/RUNNING
     * @throws ResponseStatusException (503)  if the import queue is full
     */
    public ScraperJob startImport(DataSource dataSource) {
        BusinessRecordFetcher fetcher = fetcherFor(dataSource);
        ScraperJob job;
        synchronized (startLock) {
            if (scraperJobRepository.existsBySourceIdAndStatusIn(dataSource.getId(), ACTIVE_STATUSES)) {
                throw new ImportAlreadyRunningException(dataSource.getSourceName());
            }
            job = jobTracker.createPendingJob(dataSource);
        }

        UUID jobId = job.getId();
        UUID sourceId = dataSource.getId();
        String sourceName = dataSource.getSourceName();
        try {
            importExecutor.execute(() -> jobRunner.run(jobId, sourceId, sourceName, fetcher));
        } catch (TaskRejectedException e) {
            jobTracker.fail(jobId, "Import queue is full, try again later");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Import queue is full, try again later");
        }
        log.info("Queued import job {} for {}", jobId, sourceName);
        return job;
    }

    /**
     * Resolves how to fetch records for a source. Legacy sources have dedicated services; all
     * other CKAN_API sources with a config use the generic CKAN importer.
     */
    BusinessRecordFetcher fetcherFor(DataSource dataSource) {
        return switch (dataSource.getSourceName()) {
            case DonneesQuebecCpeService.DATA_SOURCE_NAME -> cpeService::fetchBusinesses;
            case DonneesQuebecChsldService.DATA_SOURCE_NAME -> chsldService::fetchBusinesses;
            case DonneesMontrealRestaurantService.DATA_SOURCE_NAME -> montrealRestaurantService::fetchBusinesses;
            case StatCanHealthcareFacilitiesService.DATA_SOURCE_NAME -> statCanHealthcareService::fetchBusinesses;
            case PagesJaunesScraperService.DATA_SOURCE_NAME -> pagesJaunesScraperService::fetchBusinesses;
            default -> {
                if (dataSource.getSourceType() == DataSource.SourceType.CKAN_API && dataSource.getConfig() != null) {
                    // Parse the config eagerly so a broken config is reported as 400, not as a FAILED job
                    GenericCkanImportService.CkanImportConfig config = genericCkanImportService.parseConfig(dataSource);
                    yield () -> genericCkanImportService.fetchBusinesses(dataSource, config);
                }
                throw new IllegalArgumentException("Source de données non supportée: " + dataSource.getSourceName());
            }
        };
    }
}
