package com.econet.leads.service;

import com.econet.leads.config.MailSettings;
import com.econet.leads.exception.ApiException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sliding window: at most N emails per user per hour (429 beyond). */
@Component
public class EmailRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);

    private final ConcurrentHashMap<UUID, Deque<Instant>> sent = new ConcurrentHashMap<>();
    private final Clock clock;
    private final MailSettings settings;

    public EmailRateLimiter(Clock clock, MailSettings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public int limit() {
        return Math.max(1, settings.getRateLimitPerHour());
    }

    /** Reserves one send for the user or throws 429. */
    public void acquire(UUID userId) {
        Instant now = clock.instant();
        Deque<Instant> window = sent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= limit()) {
                throw ApiException.tooManyRequests("Email limit reached: at most " + limit() + " emails per hour per user");
            }
            window.addLast(now);
        }
    }

    public int sentInWindow(UUID userId) {
        Deque<Instant> window = sent.get(userId);
        if (window == null) return 0;
        Instant cutoff = clock.instant().minus(WINDOW);
        synchronized (window) {
            return (int) window.stream().filter(t -> !t.isBefore(cutoff)).count();
        }
    }
}
