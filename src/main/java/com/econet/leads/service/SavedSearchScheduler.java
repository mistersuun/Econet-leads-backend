package com.econet.leads.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily 06:00 (America/Montreal): runs the saved searches that are due and expires old Google data. */
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SavedSearchScheduler {

    private final SavedSearchService savedSearchService;
    private final PlacesService placesService;

    @Scheduled(cron = "0 0 6 * * *", zone = "America/Montreal")
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
