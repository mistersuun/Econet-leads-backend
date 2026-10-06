package com.econet.leads.integration.support;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming CSV helpers. Uses the RFC 4180 parser (quotes only, no backslash escapes: addresses
 * like "2707, rue X" and names with backslashes must not shift columns), strips a UTF-8 BOM from
 * the first header cell and resolves columns BY NAME, failing with the list of missing columns.
 */
public final class CsvSupport {

    private CsvSupport() {
    }

    public static CSVReader open(InputStream in, Charset charset, char separator) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset), 1 << 16);
        return new CSVReaderBuilder(reader)
                .withCSVParser(new RFC4180ParserBuilder().withSeparator(separator).build())
                .withKeepCarriageReturn(false)
                .build();
    }

    /** Column positions of a header row: name -> index (first occurrence), BOM and surrounding spaces removed. */
    public static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        if (header == null) return index;
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].replace("﻿", "").trim();
            index.putIfAbsent(h, i);
        }
        return index;
    }

    /**
     * Resolves logical field -> column index for the given mapping (logical name -> CSV column).
     *
     * @throws IllegalStateException listing every required column not found in the header
     */
    public static Map<String, Integer> resolve(String fileLabel, String[] header, Map<String, String> mapping,
                                               List<String> required) {
        if (header == null) {
            throw new IllegalStateException(fileLabel + " is empty (no header row)");
        }
        Map<String, Integer> index = headerIndex(header);
        Map<String, Integer> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            String column = e.getValue();
            Integer pos = column == null ? null : index.get(column);
            if (pos != null) {
                resolved.put(e.getKey(), pos);
            } else if (required.contains(e.getKey())) {
                missing.add(column + " (" + e.getKey() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(fileLabel + ": missing expected column(s) " + missing
                    + ". Columns found: " + index.keySet().stream().sorted().toList()
                    + ". Fix the column names in the data source config if the format changed.");
        }
        return resolved;
    }

    public static String get(String[] row, Map<String, Integer> columns, String field) {
        Integer i = columns.get(field);
        if (i == null || row == null || i >= row.length) return null;
        String v = row[i];
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    public static String[] readNext(CSVReader reader) throws IOException {
        try {
            return reader.readNext();
        } catch (com.opencsv.exceptions.CsvValidationException e) {
            throw new IOException("Malformed CSV at line " + reader.getLinesRead() + ": " + e.getMessage(), e);
        }
    }
}
