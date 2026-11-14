package com.econet.leads.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behind Render the app runs with server.forward-headers-strategy=framework, so the request's
 * scheme, host and remote address already reflect X-Forwarded-*; Spring's own CORS check then
 * treats a same-site browser form post as same-origin.
 *
 * Guard of the unauthenticated /public/** pages: hardening headers (noindex, no caching, strict CSP,
 * no referrer leaks of the secret token), a per-IP rate limit (default 30 requests / minute, 429
 * beyond) and an Origin check on POST (a browser form post from another site is refused; requests
 * without Origin, such as curl, are allowed since the secret token is the credential).
 */
@Component
public class PublicPagesFilter extends OncePerRequestFilter {

    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int PURGE_THRESHOLD = 5000;

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int limitPerMinute;

    public PublicPagesFilter(Clock clock, @Value("${app.public.rate-limit-per-minute:30}") int limitPerMinute) {
        this.clock = clock;
        this.limitPerMinute = Math.max(1, limitPerMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/public") || path.startsWith("/public/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");

        if (!allow(ClientIp.of(request))) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            write(response, "<!doctype html><meta charset=\"utf-8\"><title>429</title>"
                    + "<p>Trop de requêtes. Réessayez dans une minute. / Too many requests, try again in a minute.</p>");
            return;
        }
        if ("POST".equals(request.getMethod()) && !originAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            write(response, "<!doctype html><meta charset=\"utf-8\"><title>403</title><p>Requête refusée. / Request refused.</p>");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Sliding window of one minute per client address. */
    boolean allow(String ip) {
        Instant now = clock.instant();
        if (hits.size() > PURGE_THRESHOLD) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minus(WINDOW));
                }
            });
        }
        Deque<Instant> window = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String originHost = hostOf(origin);
        if (originHost == null) {
            return false;
        }
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host = forwardedHost != null && !forwardedHost.isBlank() ? forwardedHost.split(",")[0].trim() : request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + (request.getServerPort() == 80 || request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        }
        return originHost.equalsIgnoreCase(host);
    }

    /** host[:port] of a URL, or null when it is not an http(s) URL ("null" origins included). */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) return null;
            return uri.getPort() >= 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void write(HttpServletResponse response, String html) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }
}
