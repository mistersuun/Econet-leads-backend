package com.econet.leads.integration.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Lenient parsing of the date and number formats found in open-data files. */
public final class ImportValues {

    public static final ZoneId MONTREAL = ZoneId.of("America/Montreal");

    private ImportValues() {
    }

    /**
     * "2026-09-12", "2026-09-12T14:00:00", "2026-09-12 14:00:00", "2026-09-12T14:00:00Z",
     * "2026-09-12T14:00:00-04:00" (offsets converted to Montreal wall time). Null if unparseable.
     */
    public static LocalDateTime dateTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.replace(' ', 'T');
        try {
            if (s.length() == 10) {
                return LocalDate.parse(s).atStartOfDay();
            }
            if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                String iso = s.matches(".*[+-]\\d{4}$") ? s.substring(0, s.length() - 2) + ":" + s.substring(s.length() - 2) : s;
                return OffsetDateTime.parse(iso).atZoneSameInstant(MONTREAL).toLocalDateTime();
            }
            if (s.contains(".")) {
                s = s.substring(0, s.indexOf('.'));
            }
            return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s);
        } catch (DateTimeParseException e) {
            if (s.length() >= 10) {
                try {
                    return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
                } catch (DateTimeParseException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    /** "450000", "450 000,50", "450,000.50", "$450,000", "450000.0" -> BigDecimal; null if none. */
    public static BigDecimal amount(String raw) {
        if (raw == null) return null;
        String s = raw.replace(" ", " ").replace(" ", " ").replaceAll("[$\\s]", "").replace("CAD", "");
        if (s.isEmpty()) return null;
        int comma = s.lastIndexOf(','), dot = s.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            s = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            // "450,50" decimal comma vs "450,000" thousands
            s = s.length() - comma - 1 == 3 ? s.replace(",", "") : s.replace(',', '.');
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal amount(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        return amount(raw.toString());
    }

    /** 450000 -> "450 000 $" (fr-CA). */
    public static String money(BigDecimal value) {
        if (value == null) return null;
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.CANADA_FRENCH);
        return f.format(value.setScale(0, RoundingMode.HALF_UP)).replace(' ', ' ').replace(' ', ' ') + " $";
    }

    public static BigDecimal coordinate(Object raw) {
        BigDecimal v = amount(raw);
        return v == null ? null : v.setScale(8, RoundingMode.HALF_UP);
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
