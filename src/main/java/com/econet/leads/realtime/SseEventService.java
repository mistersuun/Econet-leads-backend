package com.econet.leads.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-sent events: one emitter list for the whole instance, a heartbeat comment every 25 s and
 * an in-memory ring of the last {@value #RING_SIZE} events so a reconnecting client can resume with
 * {@code Last-Event-ID} (best effort: events older than the ring are lost).
 *
 * Single-instance by design (Render free tier = 1 instance). Running several instances would need
 * the broadcasts (and the claim registry) to go through a shared bus such as Redis pub/sub.
 */
@Service
@Slf4j
public class SseEventService {

    public static final int RING_SIZE = 200;

    /** A connection changed (opened or closed); listeners re-broadcast presence. */
    public record ConnectionChanged(UUID userId, boolean connected) {
    }

    public record StoredEvent(long id, String type, String json) {
    }

    private static final class Connection {
        final UUID userId;
        final String username;
        final SseEmitter emitter;
        final Instant since;

        Connection(UUID userId, String username, SseEmitter emitter, Instant since) {
            this.userId = userId;
            this.username = username;
            this.emitter = emitter;
            this.since = since;
        }
    }

    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Deque<StoredEvent> ring = new ArrayDeque<>(RING_SIZE);
    private final AtomicLong sequence = new AtomicLong();
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final long timeoutMs;

    public SseEventService(ObjectMapper objectMapper,
                           ApplicationEventPublisher publisher,
                           Clock clock,
                           @Value("${app.sse.timeout-ms:3600000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
    }

    // ------------------------------------------------------------------ connections

    /**
     * Registers a new stream for the user. Events after {@code lastEventId} still in the ring are
     * replayed first; listeners of {@link ConnectionChanged} then send the presence snapshot.
     */
    public SseEmitter connect(UUID userId, String username, Long lastEventId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Connection connection = new Connection(userId, username, emitter, clock.instant());
        emitter.onCompletion(() -> disconnect(connection));
        emitter.onTimeout(() -> {
            disconnect(connection);
            emitter.complete();
        });
        emitter.onError(e -> disconnect(connection));
        connections.add(connection);

        if (lastEventId != null) {
            for (StoredEvent event : eventsAfter(lastEventId)) {
                send(connection, event);
            }
        }
        log.debug("SSE stream opened for {} ({} open)", username, connections.size());
        publisher.publishEvent(new ConnectionChanged(userId, true));
        return emitter;
    }

    private void disconnect(Connection connection) {
        if (connections.remove(connection)) {
            log.debug("SSE stream closed for {} ({} open)", connection.username, connections.size());
            publisher.publishEvent(new ConnectionChanged(connection.userId, false));
        }
    }

    public int openConnections() {
        return connections.size();
    }

    public Set<UUID> connectedUserIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Connection c : connections) {
            ids.add(c.userId);
        }
        return ids;
    }

    /** Earliest open connection of the user. */
    public Optional<Instant> connectedSince(UUID userId) {
        Instant earliest = null;
        for (Connection c : connections) {
            if (c.userId.equals(userId) && (earliest == null || c.since.isBefore(earliest))) {
                earliest = c.since;
            }
        }
        return Optional.ofNullable(earliest);
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Broadcasts after the current transaction commits (so clients never see an event for data
     * that was rolled back), or immediately when no transaction is active.
     */
    public void publish(String type, Object data) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcastNow(type, data);
                }
            });
        } else {
            broadcastNow(type, data);
        }
    }

    /** Serializes, stores in the ring and sends to every open stream right away. */
    public void broadcastNow(String type, Object data) {
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Cannot serialize SSE event {}", type, e);
            return;
        }
        StoredEvent event = new StoredEvent(sequence.incrementAndGet(), type, json);
        synchronized (ring) {
            if (ring.size() >= RING_SIZE) {
                ring.pollFirst();
            }
            ring.addLast(event);
        }
        for (Connection connection : connections) {
            send(connection, event);
        }
    }

    private void send(Connection connection, StoredEvent event) {
        try {
            connection.emitter.send(SseEmitter.event()
                    .id(Long.toString(event.id()))
                    .name(event.type())
                    .data(event.json()));
        } catch (IOException | IllegalStateException e) {
            drop(connection, e);
        }
    }

    private void drop(Connection connection, Exception cause) {
        log.debug("Dropping dead SSE stream of {}: {}", connection.username, cause.toString());
        disconnect(connection);
        try {
            connection.emitter.completeWithError(cause);
        } catch (Exception ignored) {
            // already closed
        }
    }

    /** Comment line every 25 s keeps proxies from closing idle streams and detects dead ones. */
    @Scheduled(fixedRateString = "${app.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        for (Connection connection : connections) {
            try {
                connection.emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                drop(connection, e);
            }
        }
    }

    public List<StoredEvent> eventsAfter(long lastEventId) {
        List<StoredEvent> result = new ArrayList<>();
        synchronized (ring) {
            for (StoredEvent e : ring) {
                if (e.id() > lastEventId) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    public long lastEventId() {
        return sequence.get();
    }
}
