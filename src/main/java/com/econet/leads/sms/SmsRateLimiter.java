package com.econet.leads.sms;

import com.econet.leads.exception.ApiException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sliding window: at most N follow-up SMS per user per hour (429 beyond). */
@Component
public class SmsRateLimiter {

    static final Duration WINDOW = Duration.ofHours(1);

    private final ConcurrentHashMap<UUID, Deque<Instant>> sent = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SmsSettings settings;

    public SmsRateLimiter(Clock clock, SmsSettings settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public int limit() {
        return Math.max(1, settings.getFollowUpLimitPerHour());
    }

    public void acquire(UUID userId) {
        Instant now = clock.instant();
        Deque<Instant> window = sent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= limit()) {
                throw ApiException.tooManyRequests("Limite atteinte : au plus " + limit() + " textos par heure par utilisateur");
            }
            window.addLast(now);
        }
    }
}
