package com.econet.leads.integration.support;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Temp files of imports (downloads, admin uploads) live in one directory so files orphaned by a
 * crash can be swept at startup.
 */
@Slf4j
public final class ImportFiles {

    private ImportFiles() {
    }

    public static Path directory() throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "econet-imports");
        Files.createDirectories(dir);
        return dir;
    }

    public static Path newTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(directory(), prefix, suffix);
    }

    public static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temp import file {}: {}", file, e.getMessage());
        }
    }

    /** Deletes every file left in the import temp directory (call only when no import is running). */
    public static int sweep() {
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory())) {
            for (Path f : files) {
                if (Files.isRegularFile(f)) {
                    deleteQuietly(f);
                    count++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not sweep import temp directory: {}", e.getMessage());
        }
        return count;
    }
}
