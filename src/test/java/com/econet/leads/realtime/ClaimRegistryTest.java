package com.econet.leads.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ClaimRegistryTest {

    /** Clock whose instant can be moved forward. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T15:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    MutableClock clock = new MutableClock();
    SseEventService events = mock(SseEventService.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    ClaimRegistry registry;
    UUID lead = UUID.randomUUID();
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = new ClaimRegistry(events, publisher, clock, 10);
    }

    @Test
    void claimIsIdempotentForTheHolderAndConflictsForOthers() {
        ClaimRegistry.Claim first = registry.claim(lead, "CPE Les Lucioles", alice, "alice");
        assertThat(first.expiresAt()).isEqualTo(clock.now.plus(Duration.ofMinutes(10)));
        verify(events).publish(eq(ClaimRegistry.EVENT_CLAIMED), any());

        clock.advance(Duration.ofMinutes(3));
        ClaimRegistry.Claim refreshed = registry.claim(lead, "CPE Les Lucioles", alice, "alice");
        assertThat(refreshed.claimedAt()).isEqualTo(first.claimedAt());
        assertThat(refreshed.expiresAt()).isEqualTo(clock.now.plus(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> registry.claim(lead, "CPE Les Lucioles", bob, "bob"))
                .isInstanceOf(ClaimConflictException.class)
                .satisfies(e -> assertThat(((ClaimConflictException) e).getClaim().username()).isEqualTo("alice"));
        assertThat(registry.leadsClaimedByOthers(bob)).containsExactly(lead);
        assertThat(registry.leadsClaimedByOthers(alice)).isEmpty();
        assertThat(registry.claimOf(alice)).isPresent();
    }

    @Test
    void releaseOnlyByHolderUnlessForced() {
        registry.claim(lead, "Bistro", alice, "alice");
        assertThat(registry.release(lead, bob)).isFalse();
        assertThat(registry.get(lead)).isPresent();
        assertThat(registry.release(lead, alice)).isTrue();
        assertThat(registry.get(lead)).isEmpty();
        verify(events).publish(ClaimRegistry.EVENT_RELEASED, Map.of("leadId", lead, "userId", alice));

        registry.claim(lead, "Bistro", alice, "alice");
        assertThat(registry.release(lead, null)).isTrue(); // a call was logged by whoever
        assertThat(registry.release(lead, null)).isFalse();
    }

    @Test
    void expiredClaimsAreSweptAndBroadcast() {
        registry.claim(lead, "Clinique", alice, "alice");
        clock.advance(Duration.ofMinutes(9));
        assertThat(registry.sweep()).isZero();
        assertThat(registry.get(lead)).isPresent();

        clock.advance(Duration.ofMinutes(2));
        assertThat(registry.get(lead)).isEmpty(); // expired even before the sweep runs
        assertThat(registry.leadsClaimedByOthers(bob)).isEmpty();
        registry.claim(lead, "Clinique", bob, "bob"); // and bob can take it
        registry.release(lead, bob);
        assertThat(registry.sweep()).isZero();

        registry.claim(lead, "Clinique", alice, "alice");
        clock.advance(Duration.ofMinutes(11));
        assertThat(registry.sweep()).isEqualTo(1);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publish(eq(ClaimRegistry.EVENT_RELEASED), payload.capture());
        assertThat(payload.getValue()).isEqualTo(Map.of("leadId", lead, "userId", alice));
        assertThat(registry.expiredClaimsOf(alice, clock.now.minus(Duration.ofHours(1))))
                .singleElement().satisfies(e -> {
                    assertThat(e.leadName()).isEqualTo("Clinique");
                    assertThat(e.expiredAt()).isEqualTo(clock.now.minus(Duration.ofMinutes(1)));
                });
        assertThat(registry.expiredClaimsOf(bob, clock.now.minus(Duration.ofHours(1)))).isEmpty();
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(any(ClaimRegistry.ClaimsChanged.class));
    }

    @Test
    void nothingToSweepPublishesNothing() {
        assertThat(registry.sweep()).isZero();
        verify(events, never()).publish(any(), any());
    }
}
