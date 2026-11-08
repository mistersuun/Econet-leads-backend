package com.econet.leads.realtime;

import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is doing what, derived from claims and open SSE streams:
 * calling = holds a claim, idle = connected without a claim, offline = no stream open.
 * Broadcast as a {@code presence} event on every connection or claim change.
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    public static final String EVENT_PRESENCE = "presence";

    public enum Status { idle, calling, offline }

    public record PresenceUser(UUID userId, String username, Status status, UUID leadId, String leadName,
                               Instant since, Instant lastActivity) {
    }

    private final ConcurrentHashMap<UUID, Instant> lastActivity = new ConcurrentHashMap<>();
    private final SseEventService events;
    private final ClaimRegistry claims;
    private final UserRepository userRepository;
    private final Clock clock;

    /** Records that the user did something (claim, call, snooze, status change). */
    public void touch(UUID userId) {
        lastActivity.put(userId, clock.instant());
    }

    public Optional<Instant> lastActivityOf(UUID userId) {
        return Optional.ofNullable(lastActivity.get(userId));
    }

    /** Presence of one user, from the registries only (no database access). */
    public PresenceUser of(User user) {
        Optional<ClaimRegistry.Claim> claim = claims.claimOf(user.getId());
        Instant activity = lastActivity.get(user.getId());
        if (claim.isPresent()) {
            ClaimRegistry.Claim c = claim.get();
            return new PresenceUser(user.getId(), user.getUsername(), Status.calling, c.leadId(), c.leadName(),
                    c.claimedAt(), activity);
        }
        Optional<Instant> connected = events.connectedSince(user.getId());
        if (connected.isPresent()) {
            return new PresenceUser(user.getId(), user.getUsername(), Status.idle, null, null, connected.get(), activity);
        }
        return new PresenceUser(user.getId(), user.getUsername(), Status.offline, null, null, activity, activity);
    }

    /** Every active ADMIN/USER account plus any other user with an open stream, by username. */
    public List<PresenceUser> snapshot() {
        Set<UUID> connected = events.connectedUserIds();
        List<PresenceUser> result = new ArrayList<>();
        for (User user : userRepository.findAll()) {
            boolean caller = Boolean.TRUE.equals(user.getActive()) && user.getRole() != User.UserRole.VIEWER;
            if (caller || connected.contains(user.getId())) {
                result.add(of(user));
            }
        }
        result.sort(Comparator.comparing(PresenceUser::username, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public void broadcast() {
        events.broadcastNow(EVENT_PRESENCE, Map.of("users", snapshot()));
    }

    @EventListener
    public void onConnectionChanged(SseEventService.ConnectionChanged event) {
        broadcast();
    }

    @EventListener
    public void onClaimsChanged(ClaimRegistry.ClaimsChanged event) {
        broadcast();
    }
}
