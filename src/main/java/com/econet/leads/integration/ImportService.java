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

import com.econet.leads.integration.support.HttpDownloader;
import com.econet.leads.integration.support.ImportFiles;

import java.nio.file.Path;
import java.time.Duration;
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
    private final MontrealPermitImporter montrealPermitImporter;
    private final QuebecBusinessRegisterImporter registerImporter;
    private final CanadaBuysTenderImporter canadaBuysImporter;
    private final SeaoTenderImporter seaoImporter;
    private final HttpDownloader downloader;
    private final CkanApiClient ckanApiClient;

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
                         GenericCkanImportService genericCkanImportService,
                         MontrealPermitImporter montrealPermitImporter,
                         QuebecBusinessRegisterImporter registerImporter,
                         CanadaBuysTenderImporter canadaBuysImporter,
                         SeaoTenderImporter seaoImporter,
                         HttpDownloader downloader,
                         CkanApiClient ckanApiClient) {
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
        this.montrealPermitImporter = montrealPermitImporter;
        this.registerImporter = registerImporter;
        this.canadaBuysImporter = canadaBuysImporter;
        this.seaoImporter = seaoImporter;
        this.downloader = downloader;
        this.ckanApiClient = ckanApiClient;
    }

    /** Work of one job, given its id (runs on the import executor). */
    @FunctionalInterface
    interface JobTask {
        void run(UUID jobId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failOrphanedJobsOnStartup() {
        int count = jobTracker.failOrphanedJobs();
        if (count > 0) {
            log.warn("Marked {} import job(s) left PENDING/RUNNING by a previous run as FAILED", count);
        }
        // Downloads/uploads of those jobs (e.g. a 225 MB register ZIP) are useless now
        int files = ImportFiles.sweep();
        if (files > 0) {
            log.warn("Deleted {} temp import file(s) left by a previous run", files);
        }
    }

    /**
     * @throws IllegalArgumentException       if the source type is not supported or its config is invalid
     * @throws ImportAlreadyRunningException  if an import of this source is PENDING/RUNNING
     * @throws ResponseStatusException (503)  if the import queue is full
     */
    public ScraperJob startImport(DataSource dataSource) {
        return start(dataSource, taskFor(dataSource, null));
    }

    /**
     * Starts an import of an uploaded file (Registre des entreprises ZIP). The file is moved under the
     * import temp directory by the caller and deleted when the job ends, whatever the outcome.
     */
    public ScraperJob startImportFromFile(DataSource dataSource, Path uploadedFile) {
        if (!QuebecBusinessRegisterImporter.IMPORTER.equals(importerOf(dataSource))) {
            ImportFiles.deleteQuietly(uploadedFile);
            throw new IllegalArgumentException("File upload is only supported for the business register source, not "
                    + dataSource.getSourceName());
        }
        try {
            return start(dataSource, taskFor(dataSource, uploadedFile));
        } catch (RuntimeException e) {
            ImportFiles.deleteQuietly(uploadedFile);
            throw e;
        }
    }

    public boolean isImportRunning(DataSource dataSource) {
        return scraperJobRepository.existsBySourceIdAndStatusIn(dataSource.getId(), ACTIVE_STATUSES);
    }

    public static String importerOf(DataSource dataSource) {
        Object importer = dataSource.getConfig() != null ? dataSource.getConfig().get("importer") : null;
        return importer != null ? importer.toString() : null;
    }

    private ScraperJob start(DataSource dataSource, JobTask task) {
        ScraperJob job;
        synchronized (startLock) {
            if (scraperJobRepository.existsBySourceIdAndStatusIn(dataSource.getId(), ACTIVE_STATUSES)) {
                throw new ImportAlreadyRunningException(dataSource.getSourceName());
            }
            job = jobTracker.createPendingJob(dataSource);
        }

        UUID jobId = job.getId();
        String sourceName = dataSource.getSourceName();
        try {
            importExecutor.execute(() -> task.run(jobId));
        } catch (TaskRejectedException e) {
            jobTracker.fail(jobId, "Import queue is full, try again later");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Import queue is full, try again later");
        }
        log.info("Queued import job {} for {}", jobId, sourceName);
        return job;
    }

    /**
     * Resolves how to import a source. Sources whose config names an "importer" use it; legacy
     * sources have dedicated services; all other CKAN_API sources with a config use the generic
     * CKAN importer. Configs are parsed eagerly so a broken config is reported as 400, not as a
     * FAILED job.
     */
    JobTask taskFor(DataSource dataSource, Path uploadedFile) {
        UUID sourceId = dataSource.getId();
        String name = dataSource.getSourceName();
        String importer = importerOf(dataSource);
        if (importer != null) {
            ImportReport report = new ImportReport();
            switch (importer) {
                case MontrealPermitImporter.IMPORTER -> {
                    var settings = montrealPermitImporter.parse(dataSource);
                    return jobId -> jobRunner.runBusinesses(jobId, sourceId, name,
                            montrealPermitImporter.stream(dataSource, settings, report), report);
                }
                case QuebecBusinessRegisterImporter.IMPORTER -> {
                    var settings = registerImporter.parse(dataSource);
                    if (uploadedFile == null && settings.downloadUrl() == null && settings.resourceId() == null) {
                        throw new IllegalArgumentException("No downloadUrl/resourceId configured for " + name
                                + ": upload the ZIP with POST /api/data-sources/" + sourceId + "/upload");
                    }
                    return jobId -> jobRunner.runBusinesses(jobId, sourceId, name, sink -> {
                        Path zip = uploadedFile != null ? uploadedFile : downloadRegister(settings, report);
                        try {
                            report.note(uploadedFile != null ? "Source: uploaded file" : "Source: downloaded archive");
                            registerImporter.stream(zip, dataSource, settings, report, new QuebecBusinessRegisterImporter.Stats())
                                    .forEach(sink);
                        } finally {
                            ImportFiles.deleteQuietly(zip);
                        }
                    }, report);
                }
                case CanadaBuysTenderImporter.IMPORTER -> {
                    var settings = canadaBuysImporter.parse(dataSource);
                    return jobId -> jobRunner.runTenders(jobId, sourceId, name, canadaBuysImporter.stream(settings, report), report);
                }
                case SeaoTenderImporter.IMPORTER -> {
                    var settings = seaoImporter.parse(dataSource);
                    return jobId -> jobRunner.runTenders(jobId, sourceId, name, seaoImporter.stream(settings, report), report);
                }
                default -> throw new IllegalArgumentException("Unknown importer '" + importer + "' for " + name);
            }
        }
        BusinessRecordFetcher fetcher = legacyFetcherFor(dataSource);
        return jobId -> jobRunner.run(jobId, sourceId, name, fetcher);
    }

    /** downloadUrl, or the URL of CKAN resource resourceId (resource_show). */
    private Path downloadRegister(QuebecBusinessRegisterImporter.Settings settings, ImportReport report) throws java.io.IOException {
        String url = settings.downloadUrl();
        if (url == null) {
            url = ckanApiClient.action(settings.ckanBaseUrl(), "resource_show", java.util.Map.of("id", settings.resourceId()))
                    .path("url").asText(null);
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("CKAN resource " + settings.resourceId() + " has no download URL; set downloadUrl or upload the ZIP");
            }
        }
        report.note("Downloading " + url);
        return downloader.downloadToTempFile(url, ".zip", Duration.ofMinutes(30));
    }

    BusinessRecordFetcher legacyFetcherFor(DataSource dataSource) {
        return switch (dataSource.getSourceName()) {
            case DonneesQuebecCpeService.DATA_SOURCE_NAME -> cpeService::fetchBusinesses;
            case DonneesQuebecChsldService.DATA_SOURCE_NAME -> chsldService::fetchBusinesses;
            case DonneesMontrealRestaurantService.DATA_SOURCE_NAME -> montrealRestaurantService::fetchBusinesses;
            case StatCanHealthcareFacilitiesService.DATA_SOURCE_NAME -> statCanHealthcareService::fetchBusinesses;
            case PagesJaunesScraperService.DATA_SOURCE_NAME -> pagesJaunesScraperService::fetchBusinesses;
            default -> {
                if (dataSource.getSourceType() == DataSource.SourceType.CKAN_API && dataSource.getConfig() != null) {
                    GenericCkanImportService.CkanImportConfig config = genericCkanImportService.parseConfig(dataSource);
                    yield () -> genericCkanImportService.fetchBusinesses(dataSource, config);
                }
                throw new IllegalArgumentException("Source de données non supportée: " + dataSource.getSourceName());
            }
        };
    }
}
