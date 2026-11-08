package com.econet.leads.integration.website;

import com.econet.leads.integration.ImportAlreadyRunningException;
import com.econet.leads.integration.ImportService;
import com.econet.leads.model.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Polls the website every {@code app.website.poll-minutes} (default 5) while it is configured and the source is active. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebsitePoller {

    private final WebsiteDb websiteDb;
    private final WebsiteRequestsService service;
    private final ImportService importService;

    @Scheduled(fixedDelayString = "${app.website.poll-minutes:5}", initialDelayString = "${app.website.poll-minutes:5}",
            timeUnit = TimeUnit.MINUTES)
    public void poll() {
        if (!websiteDb.isConfigured()) {
            return;
        }
        DataSource source = service.source().orElse(null);
        if (source == null || !Boolean.TRUE.equals(source.getActive())) {
            return;
        }
        try {
            importService.startImport(source);
        } catch (ImportAlreadyRunningException e) {
            log.debug("Website poll skipped: previous import still running");
        } catch (RuntimeException e) {
            log.warn("Website poll failed to start: {}", e.getMessage());
        }
    }
}
