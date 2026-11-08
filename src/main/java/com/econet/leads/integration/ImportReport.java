package com.econet.leads.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Non-fatal notes an importer wants the admin to see on the job (stored in scraper_jobs.log),
 * e.g. "12 345 rows read, 87 matched the filters" or "column X absent: filter skipped".
 */
public class ImportReport {

    static final int MAX_LINES = 50;

    private final int maxLines;
    private final List<String> lines = new ArrayList<>();
    private volatile Consumer<Totals> completionHook;
    private volatile Integer skipped;
    private volatile Integer invalid;

    /** Importers that reject or skip rows record the counts here; stored on the job as skipped / invalid. */
    public void setRowCounts(int skipped, int invalid) {
        this.skipped = skipped;
        this.invalid = invalid;
    }

    Integer skipped() { return skipped; }

    Integer invalid() { return invalid; }

    /** Counters of a finished run, handed to the completion hook. */
    public record Totals(int processed, int added, int updated, int failed) {
    }

    public ImportReport() {
        this(MAX_LINES);
    }

    /** For importers that list one line per rejected row (file imports keep the first 50 reasons plus a summary). */
    public ImportReport(int maxLines) {
        this.maxLines = maxLines;
    }

    /** Called by the runner just before the job is completed, e.g. to write a summary line first in the log. */
    public void onComplete(Consumer<Totals> hook) {
        this.completionHook = hook;
    }

    void fireComplete(Totals totals) {
        Consumer<Totals> hook = completionHook;
        if (hook != null) {
            hook.accept(totals);
        }
    }

    /** Inserts a line at the top (always kept, even past the line limit). */
    public synchronized void prepend(String line) {
        lines.add(0, line);
    }

    public synchronized void note(String line) {
        if (lines.size() < maxLines) {
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
