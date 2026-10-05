package com.econet.leads.integration;

import java.util.ArrayList;
import java.util.List;

/**
 * Non-fatal notes an importer wants the admin to see on the job (stored in scraper_jobs.log),
 * e.g. "12 345 rows read, 87 matched the filters" or "column X absent: filter skipped".
 */
public class ImportReport {

    static final int MAX_LINES = 50;

    private final List<String> lines = new ArrayList<>();

    public synchronized void note(String line) {
        if (lines.size() < MAX_LINES) {
            lines.add(line);
        }
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    public synchronized String asText() {
        return lines.isEmpty() ? null : String.join("\n", lines);
    }
}
