package com.econet.leads.service;

import com.econet.leads.exception.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and merges the Qualification JSON stored on a lead (businesses.qualification):
 * <pre>
 * decisionMaker, role, directPhone, email, currentProvider, bestTimeToCall, notes : string
 * contractEndsAt : YYYY-MM-DD        sizeSqft, employees : integer >= 0
 * frequencyWanted : weekly | bi-weekly | monthly | one-time
 * painPoints : string[]              language : fr | en
 * </pre>
 * A PATCH is partial: keys absent are untouched, a null (or blank) value clears the key.
 */
@Service
public class QualificationService {

    public static final Set<String> STRING_KEYS = Set.of(
            "decisionMaker", "role", "directPhone", "email", "currentProvider", "bestTimeToCall", "notes");
    public static final Set<String> INTEGER_KEYS = Set.of("sizeSqft", "employees");
    public static final Set<String> FREQUENCIES = Set.of("weekly", "bi-weekly", "monthly", "one-time");
    public static final Set<String> LANGUAGES = Set.of("fr", "en");
    static final int MAX_STRING = 500;
    static final int MAX_NOTES = 5000;
    static final int MAX_PAIN_POINTS = 20;

    /** Existing + patch; never null. */
    public Map<String, Object> merge(Map<String, Object> existing, Map<String, Object> patch) {
        Map<String, Object> result = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        if (patch == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = normalize(key, entry.getValue());
            if (value == null) {
                result.remove(key);
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /** Validates one field; returns the canonical value or null when the field is being cleared. */
    static Object normalize(String key, Object raw) {
        if (raw == null) {
            return null;
        }
        if (STRING_KEYS.contains(key)) {
            String s = string(key, raw, "notes".equals(key) ? MAX_NOTES : MAX_STRING);
            if (s != null && "email".equals(key) && !s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw ApiException.badRequest("qualification.email is not a valid email address");
            }
            return s;
        }
        if (INTEGER_KEYS.contains(key)) {
            if (raw instanceof String s && s.isBlank()) {
                return null;
            }
            if (!(raw instanceof Number) && !(raw instanceof String)) {
                throw ApiException.badRequest("qualification." + key + " must be a number");
            }
            long v;
            try {
                v = raw instanceof Number n ? Math.round(n.doubleValue()) : Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("qualification." + key + " must be a number");
            }
            if (v < 0 || v > 100_000_000L) {
                throw ApiException.badRequest("qualification." + key + " must be between 0 and 100000000");
            }
            return v;
        }
        switch (key) {
            case "contractEndsAt" -> {
                String s = string(key, raw, 10);
                if (s == null) return null;
                try {
                    return LocalDate.parse(s).toString();
                } catch (DateTimeParseException e) {
                    throw ApiException.badRequest("qualification.contractEndsAt must be a date YYYY-MM-DD");
                }
            }
            case "frequencyWanted" -> {
                String s = string(key, raw, 20);
                if (s == null) return null;
                s = s.toLowerCase();
                if (!FREQUENCIES.contains(s)) {
                    throw ApiException.badRequest("qualification.frequencyWanted must be one of weekly, bi-weekly, monthly, one-time");
                }
                return s;
            }
            case "language" -> {
                String s = string(key, raw, 2);
                if (s == null) return null;
                s = s.toLowerCase();
                if (!LANGUAGES.contains(s)) {
                    throw ApiException.badRequest("qualification.language must be fr or en");
                }
                return s;
            }
            case "painPoints" -> {
                if (!(raw instanceof List<?> list)) {
                    throw ApiException.badRequest("qualification.painPoints must be an array of strings");
                }
                List<String> points = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof String str)) {
                        throw ApiException.badRequest("qualification.painPoints must be an array of strings");
                    }
                    String t = str.trim();
                    if (!t.isEmpty() && !points.contains(t)) {
                        points.add(t.length() > 100 ? t.substring(0, 100) : t);
                    }
                }
                if (points.size() > MAX_PAIN_POINTS) {
                    throw ApiException.badRequest("qualification.painPoints must have at most " + MAX_PAIN_POINTS + " entries");
                }
                return points.isEmpty() ? null : points;
            }
            default -> throw ApiException.badRequest("Unknown qualification field '" + key + "'");
        }
    }

    private static String string(String key, Object raw, int max) {
        if (!(raw instanceof String s)) {
            throw ApiException.badRequest("qualification." + key + " must be a string");
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > max) {
            throw ApiException.badRequest("qualification." + key + " must not exceed " + max + " characters");
        }
        return t;
    }

    /** Convenience for templates/CSV: the string value of a key, or null. */
    public static String text(Map<String, Object> qualification, String key) {
        if (qualification == null) return null;
        Object v = qualification.get(key);
        return v == null ? null : v.toString();
    }
}
