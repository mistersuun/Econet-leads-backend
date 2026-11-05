package com.econet.leads.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "No two callers on the same business": in-memory claims with a 10-minute TTL, refreshed by
 * claiming again, released explicitly or by logging a call / snoozing / changing the status, and
 * swept by a scheduled task that broadcasts {@code lead.released} for expired claims.
 *
 * In-memory on purpose (one instance); see {@link SseEventService} for the multi-instance note.
 */
@Component
@Slf4j
public class ClaimRegistry {

    public static final String EVENT_CLAIMED = "lead.claimed";
    public static final String EVENT_RELEASED = "lead.released";
    private static final int EXPIRED_HISTORY = 200;

    public record Claim(UUID leadId, String leadName, UUID userId, String username, Instant claimedAt, Instant expiresAt) {
        public boolean isActive(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    /** A claim that expired without being released (for the claim_expired notification). */
    public record ExpiredClaim(UUID leadId, String leadName, UUID userId, Instant expiredAt) {
    }

    /** The set of claims changed; listeners re-broadcast presence. */
    public record ClaimsChanged() {
    }

    private final ConcurrentHashMap<UUID, Claim> claims = new ConcurrentHashMap<>();
    private final Deque<ExpiredClaim> expired = new ArrayDeque<>();
    private final SseEventService events;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final Duration ttl;

    public ClaimRegistry(SseEventService events,
                         ApplicationEventPublisher publisher,
                         Clock clock,
                         @Value("${app.claims.ttl-minutes:10}") long ttlMinutes) {
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public Duration getTtl() {
        return ttl;
    }

    /**
     * Claims (or refreshes the claim on) a lead for the user.
     *
     * @throws ClaimConflictException when another user holds an unexpired claim
     */
    public Claim claim(UUID leadId, String leadName, UUID userId, String username) {
        Instant now = clock.instant();
        Claim[] conflict = new Claim[1];
        Claim result = claims.compute(leadId, (id, existing) -> {
            if (existing != null && existing.isActive(now) && !existing.userId().equals(userId)) {
                conflict[0] = existing;
                return existing;
            }
            Instant claimedAt = existing != null && existing.isActive(now) ? existing.claimedAt() : now;
            return new Claim(leadId, leadName, userId, username, claimedAt, now.plus(ttl));
        });
        if (conflict[0] != null) {
            throw new ClaimConflictException(conflict[0]);
        }
        events.publish(EVENT_CLAIMED, claimedPayload(result));
        publisher.publishEvent(new ClaimsChanged());
        return result;
    }

    /** Active claim on the lead, if any. */
    public Optional<Claim> get(UUID leadId) {
        Claim claim = claims.get(leadId);
        return claim != null && claim.isActive(clock.instant()) ? Optional.of(claim) : Optional.empty();
    }

    /** First active claim held by the user (a caller works one lead at a time). */
    public Optional<Claim> claimOf(UUID userId) {
        Instant now = clock.instant();
        return claims.values().stream()
                .filter(c -> c.userId().equals(userId) && c.isActive(now))
                .findFirst();
    }

    /** Leads currently claimed by someone other than the user (excluded from their queue). */
    public Set<UUID> leadsClaimedByOthers(UUID userId) {
        Instant now = clock.instant();
        Set<UUID> result = new HashSet<>();
        for (Claim c : claims.values()) {
            if (c.isActive(now) && !c.userId().equals(userId)) {
                result.add(c.leadId());
            }
        }
        return result;
    }

    /**
     * Releases the claim on the lead. With a user id, only that user's claim is released (anyone
     * else's is left alone); with null, whoever holds it is released (a call was logged, the lead
     * changed hands).
     *
     * @return true if a claim was removed
     */
    public boolean release(UUID leadId, UUID userId) {
        Claim[] removed = new Claim[1];
        claims.computeIfPresent(leadId, (id, existing) -> {
            if (userId == null || existing.userId().equals(userId)) {
                removed[0] = existing;
                return null;
            }
            return existing;
        });
        if (removed[0] == null) {
            return false;
        }
        events.publish(EVENT_RELEASED, Map.of("leadId", leadId, "userId", removed[0].userId()));
        publisher.publishEvent(new ClaimsChanged());
        return true;
    }

    /** Drops every claim without broadcasting (tests, admin reset). */
    public void clearAll() {
        claims.clear();
        synchronized (expired) {
            expired.clear();
        }
    }

    /** Expired claims of the user since the given instant, newest first. */
    public List<ExpiredClaim> expiredClaimsOf(UUID userId, Instant since) {
        List<ExpiredClaim> result = new ArrayList<>();
        synchronized (expired) {
            for (ExpiredClaim e : expired) {
                if (e.userId().equals(userId) && !e.expiredAt().isBefore(since)) {
                    result.add(e);
                }
            }
        }
        Collections.reverse(result);
        return result;
    }

    /** Removes expired claims and broadcasts their release. */
    @Scheduled(fixedRateString = "${app.claims.sweep-ms:30000}")
    public int sweep() {
        Instant now = clock.instant();
        int swept = 0;
        for (Claim claim : new ArrayList<>(claims.values())) {
            if (!claim.isActive(now) && claims.remove(claim.leadId(), claim)) {
                swept++;
                synchronized (expired) {
                    if (expired.size() >= EXPIRED_HISTORY) {
                        expired.pollFirst();
                    }
                    expired.addLast(new ExpiredClaim(claim.leadId(), claim.leadName(), claim.userId(), claim.expiresAt()));
                }
                log.info("Claim on lead {} by {} expired", claim.leadId(), claim.username());
                events.publish(EVENT_RELEASED, Map.of("leadId", claim.leadId(), "userId", claim.userId()));
            }
        }
        if (swept > 0) {
            publisher.publishEvent(new ClaimsChanged());
        }
        return swept;
    }

    public static Map<String, Object> claimedPayload(Claim claim) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leadId", claim.leadId());
        payload.put("leadName", claim.leadName());
        payload.put("userId", claim.userId());
        payload.put("username", claim.username());
        payload.put("expiresAt", claim.expiresAt());
        return payload;
    }
}
