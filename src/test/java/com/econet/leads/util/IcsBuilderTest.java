package com.econet.leads.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class IcsBuilderTest {

    @Test
    void buildsAOneHourUtcEventWithEscapedTextAndFoldedLines() {
        // 2026-11-10 14:00 Montreal (EST, UTC-5) -> 19:00Z
        String ics = IcsBuilder.build("abc@econet-leads", "Visite EcoNet Propreté – CPE Les Lucioles; annexe",
                "123 Rue Saint-Denis, Montréal, QC H2X 1K1", "Ligne 1\nLigne 2",
                LocalDateTime.of(2026, 11, 10, 14, 0), 60,
                "EcoNet Propreté", "ventes@example.com", "Mme Tremblay", "tremblay@example.com",
                Instant.parse("2026-11-05T15:00:00Z"));
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(ics).contains("DTSTART:20261110T190000Z\r\n").contains("DTEND:20261110T200000Z\r\n");
        assertThat(ics).contains("SUMMARY:Visite EcoNet Propreté – CPE Les Lucioles\\; annexe\r\n");
        assertThat(ics).contains("LOCATION:123 Rue Saint-Denis\\, Montréal\\, QC H2X 1K1\r\n");
        assertThat(ics).contains("DESCRIPTION:Ligne 1\\nLigne 2\r\n");
        assertThat(ics).contains("ATTENDEE;CN=\"Mme Tremblay\";ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:tremblay@e\r\n xample.com\r\n");
        assertThat(ics).endsWith("END:VCALENDAR\r\n");
        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }

    @Test
    void summerTimeIsHandled() {
        // 2026-07-01 09:30 Montreal (EDT, UTC-4) -> 13:30Z
        String ics = IcsBuilder.build("u", "Visite", null, null, LocalDateTime.of(2026, 7, 1, 9, 30), 60,
                null, null, null, null, Instant.EPOCH);
        assertThat(ics).contains("DTSTART:20260701T133000Z").contains("DTEND:20260701T143000Z");
        assertThat(ics).doesNotContain("LOCATION").doesNotContain("ORGANIZER");
    }
}
