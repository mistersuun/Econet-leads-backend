package com.econet.leads.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Monday 07:30 America/Montreal; does nothing while SMTP is not configured. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportScheduler {

    private final WeeklyReportService service;

    @Scheduled(cron = "${app.weekly-report.cron:0 30 7 * * MON}", zone = "America/Montreal")
    public void run() {
        if (!service.mailConfigured()) {
            log.info("Weekly report skipped: SMTP is not configured");
            return;
        }
        try {
            service.sendAll();
        } catch (RuntimeException e) {
            log.warn("Weekly report run failed: {}", e.getClass().getSimpleName());
        }
    }
}
