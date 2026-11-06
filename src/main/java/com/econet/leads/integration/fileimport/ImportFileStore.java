package com.econet.leads.integration.fileimport;

import com.econet.leads.integration.support.ImportFiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploaded files waiting for their column mapping. Each upload is a temp file in the import
 * directory plus its metadata in memory (single instance); entries expire one hour after the upload
 * and a sweep deletes the files. The file is also deleted when its import ends, whatever the outcome.
 */
@Component
@Slf4j
public class ImportFileStore {

    public static final Duration TTL = Duration.ofHours(1);

    /** Metadata returned to the client (POST /api/imports/files, GET /api/imports/{id}). */
    public record UploadInfo(String importId, String fileName, int rowCount, List<String> columns,
                             List<List<String>> sampleRows, Map<String, String> suggestedMapping) {
    }

    public static final class Entry {
        final String id;
        final Path file;
        final TabularFile.Layout layout;
        final UploadInfo info;
        final Instant createdAt;
        final AtomicBoolean running = new AtomicBoolean();

        Entry(String id, Path file, TabularFile.Layout layout, UploadInfo info, Instant createdAt) {
            this.id = id;
            this.file = file;
            this.layout = layout;
            this.info = info;
            this.createdAt = createdAt;
        }

        public String id() { return id; }
        public Path file() { return file; }
        public TabularFile.Layout layout() { return layout; }
        public UploadInfo info() { return info; }
        public boolean tryStart() { return running.compareAndSet(false, true); }
        public void finish() { running.set(false); }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Entry put(Path file, String fileName, TabularFile.Layout layout) {
        String id = UUID.randomUUID().toString();
        UploadInfo info = new UploadInfo(id, fileName, layout.rowCount(), layout.columns(), layout.sampleRows(),
                HeaderSynonyms.suggest(layout.columns()));
        Entry entry = new Entry(id, file, layout, info, Instant.now());
        entries.put(id, entry);
        return entry;
    }

    public Optional<Entry> get(String id) {
        Entry entry = id == null ? null : entries.get(id);
        if (entry != null && entry.createdAt.plus(TTL).isBefore(Instant.now()) && !entry.running.get()) {
            remove(id);
            return Optional.empty();
        }
        return Optional.ofNullable(entry);
    }

    public void remove(String id) {
        Entry entry = entries.remove(id);
        if (entry != null) {
            ImportFiles.deleteQuietly(entry.file);
        }
    }

    public int size() {
        return entries.size();
    }

    @Scheduled(fixedDelayString = "${app.import.upload-sweep-ms:300000}")
    public void sweep() {
        sweep(Instant.now());
    }

    /** Deletes uploads older than {@link #TTL} at {@code now} (never one whose import is running). */
    public int sweep(Instant now) {
        int removed = 0;
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.createdAt.plus(TTL).isBefore(now) && !entry.running.get()) {
                remove(entry.id);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Deleted {} expired import upload(s)", removed);
        }
        return removed;
    }
}
