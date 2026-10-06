package com.econet.leads.integration.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Streams remote files to disk (never into memory) and fetches small JSON/text documents.
 * Honors the JVM proxy settings (https.proxyHost...). Failures are reported with the URL and the
 * HTTP status so a FAILED import job says exactly what went wrong.
 */
@Component
@Slf4j
public class HttpDownloader {

    static final String USER_AGENT = "EconetLeads/1.0 (+lead import; contact: admin)";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .build();

    /** Downloads {@code url} into a new temp file (caller deletes it). */
    public Path downloadToTempFile(String url, String suffix, Duration timeout) throws IOException {
        Path target = ImportFiles.newTempFile("download-", suffix);
        try {
            HttpResponse<Path> response = client.send(request(url, timeout), HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() / 100 != 2) {
                String body = preview(target);
                throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + url
                        + (body.isEmpty() ? "" : " — " + body));
            }
            log.info("Downloaded {} ({} bytes)", url, Files.size(target));
            return target;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw wrap(url, e);
        } catch (InterruptedException e) {
            Files.deleteIfExists(target);
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    public String getText(String url, Duration timeout) throws IOException {
        try {
            HttpResponse<String> response = client.send(request(url, timeout), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                String body = response.body() == null ? "" : response.body().strip();
                throw new IOException("HTTP " + response.statusCode() + " for " + url
                        + (body.isEmpty() ? "" : " — " + body.substring(0, Math.min(200, body.length()))));
            }
            return response.body();
        } catch (IOException | RuntimeException e) {
            throw wrap(url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted: " + url, e);
        }
    }

    private static HttpRequest request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .GET()
                .build();
    }

    private static IOException wrap(String url, Exception e) {
        if (e instanceof IOException io && io.getMessage() != null && io.getMessage().contains(url)) {
            return io;
        }
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new IOException("Cannot download " + url + ": " + reason, e);
    }

    private static String preview(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(200);
            return new String(bytes, StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
        } catch (IOException e) {
            return "";
        }
    }
}
