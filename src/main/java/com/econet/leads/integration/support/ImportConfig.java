package com.econet.leads.integration.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed, defaulting view over a data source's JSON config (data_sources.config). Every external
 * field name and filter lives there so a format change can be fixed with an UPDATE, not a deploy.
 */
public final class ImportConfig {

    private final String sourceName;
    private final Map<String, Object> map;

    public ImportConfig(String sourceName, Map<String, Object> map) {
        this.sourceName = sourceName;
        this.map = map != null ? map : Collections.emptyMap();
    }

    public String sourceName() {
        return sourceName;
    }

    public Map<String, Object> raw() {
        return map;
    }

    public String string(String key, String defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        String s = v.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }

    public String requireString(String key) {
        String v = string(key, null);
        if (v == null || "to_be_configured".equals(v)) {
            throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "' is missing");
        }
        return v;
    }

    public int integer(String key, int defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "' must be an integer");
        }
    }

    public Double decimal(String key, Double defaultValue) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v == null || v.toString().isBlank()) return defaultValue;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "' must be a number");
        }
    }

    public boolean bool(String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v.toString().trim());
    }

    public List<String> strings(String key, List<String> defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) result.add(o.toString().trim());
            }
            return result;
        }
        throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "' must be a list");
    }

    public ImportConfig section(String key) {
        Object v = map.get(key);
        if (v == null) return new ImportConfig(sourceName + "." + key, Map.of());
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, val) -> copy.put(String.valueOf(k), val));
            return new ImportConfig(sourceName + "." + key, copy);
        }
        throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "' must be an object");
    }

    /** List of objects (e.g. sectors). */
    public List<ImportConfig> sections(String key) {
        Object v = map.get(key);
        if (v == null) return List.of();
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "' must be a list of objects");
        }
        List<ImportConfig> result = new ArrayList<>();
        int i = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Invalid configuration for " + sourceName + ": '" + key + "[" + i + "]' must be an object");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, val) -> copy.put(String.valueOf(k), val));
            result.add(new ImportConfig(sourceName + "." + key + "[" + i + "]", copy));
            i++;
        }
        return result;
    }

    /** Map of string -> list of strings (e.g. city aliases). */
    public Map<String, List<String>> stringLists(String key) {
        ImportConfig s = section(key);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String k : s.raw().keySet()) {
            result.put(k, s.strings(k, List.of()));
        }
        return result;
    }
}
