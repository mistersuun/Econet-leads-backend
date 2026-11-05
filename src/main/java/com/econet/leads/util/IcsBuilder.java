package com.econet.leads.util;

import com.econet.leads.config.TimeConfig;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written iCalendar invite (RFC 5545): one VEVENT in UTC, CRLF line endings, lines folded at
 * 75 octets, text values escaped. Good enough for Outlook, Gmail and Apple Calendar.
 */
public final class IcsBuilder {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private IcsBuilder() {
    }

    /**
     * @param startMontreal wall-clock start in America/Montreal; converted to UTC
     */
    public static String build(String uid, String summary, String location, String description,
                               LocalDateTime startMontreal, int durationMinutes,
                               String organizerName, String organizerEmail,
                               String attendeeName, String attendeeEmail, Instant now) {
        Instant start = startMontreal.atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant end = start.plusSeconds(durationMinutes * 60L);
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:-//Econet Leads//Visite//FR");
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:REQUEST");
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + uid);
        lines.add("DTSTAMP:" + UTC.format(now.atOffset(ZoneOffset.UTC)));
        lines.add("DTSTART:" + UTC.format(start.atOffset(ZoneOffset.UTC)));
        lines.add("DTEND:" + UTC.format(end.atOffset(ZoneOffset.UTC)));
        lines.add("SUMMARY:" + escape(summary));
        if (location != null && !location.isBlank()) {
            lines.add("LOCATION:" + escape(location));
        }
        if (description != null && !description.isBlank()) {
            lines.add("DESCRIPTION:" + escape(description));
        }
        if (organizerEmail != null && !organizerEmail.isBlank()) {
            lines.add("ORGANIZER;CN=" + quoteParam(organizerName) + ":mailto:" + organizerEmail);
        }
        if (attendeeEmail != null && !attendeeEmail.isBlank()) {
            lines.add("ATTENDEE;CN=" + quoteParam(attendeeName != null ? attendeeName : attendeeEmail)
                    + ";ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:" + attendeeEmail);
        }
        lines.add("STATUS:CONFIRMED");
        lines.add("SEQUENCE:0");
        lines.add("BEGIN:VALARM");
        lines.add("TRIGGER:-PT60M");
        lines.add("ACTION:DISPLAY");
        lines.add("DESCRIPTION:" + escape(summary));
        lines.add("END:VALARM");
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(fold(line)).append("\r\n");
        }
        return sb.toString();
    }

    /** Escapes backslash, semicolon, comma and newlines (RFC 5545 §3.3.11). */
    static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    private static String quoteParam(String value) {
        String v = value == null ? "" : value.replace("\"", "").replace("\n", " ");
        return "\"" + v + "\"";
    }

    /** Folds at 75 octets (UTF-8), continuation lines start with a space. */
    static String fold(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        int limit = 75;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int len = ch.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + len > limit) {
                sb.append(current).append("\r\n ");
                current.setLength(0);
                currentBytes = 0;
                limit = 74; // the leading space counts
            }
            current.append(ch);
            currentBytes += len;
            i += Character.charCount(cp);
        }
        sb.append(current);
        return sb.toString();
    }
}
