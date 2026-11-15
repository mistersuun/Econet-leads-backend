package com.econet.leads.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the saved searches that are due and expires old Google data. The free server sleeps at 06:00,
 * so the check runs hourly (06:05 Montréal onwards) and when the server starts; both are idempotent
 * (a search is due once per day, and a purge of already purged data does nothing).
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SavedSearchScheduler {

    private final SavedSearchService savedSearchService;
    private final PlacesService placesService;

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void catchUpAtStartup() {
        // off the startup thread: a run can take minutes and must not delay the readiness of the server
        Thread t = new Thread(this::daily, "saved-search-catch-up");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "0 5 6-23 * * *", zone = "America/Montreal")
    public void daily() {
        try {
            savedSearchService.runDue();
        } catch (RuntimeException e) {
            log.warn("Saved search run failed: {}", e.toString());
        }
        try {
            placesService.purgeExpired();
        } catch (RuntimeException e) {
            log.warn("Places purge failed: {}", e.toString());
        }
    }
}
