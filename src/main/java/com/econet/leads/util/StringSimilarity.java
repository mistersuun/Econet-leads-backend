package com.econet.leads.util;

import org.apache.commons.text.similarity.LevenshteinDistance;

public class StringSimilarity {

    private static final LevenshteinDistance LEVENSHTEIN = new LevenshteinDistance();

    /**
     * Calculate similarity between two strings (0.0 to 1.0)
     * Uses Levenshtein distance algorithm
     */
    public static double calculate(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }

        if (s1.equals(s2)) {
            return 1.0;
        }

        String str1 = s1.toLowerCase().trim();
        String str2 = s2.toLowerCase().trim();

        if (str1.isEmpty() || str2.isEmpty()) {
            return 0.0;
        }

        int maxLength = Math.max(str1.length(), str2.length());
        int distance = LEVENSHTEIN.apply(str1, str2);

        return 1.0 - ((double) distance / maxLength);
    }

    /**
     * Check if two strings are similar above a threshold
     */
    public static boolean isSimilar(String s1, String s2, double threshold) {
        return calculate(s1, s2) >= threshold;
    }

    /**
     * Normalize string for comparison (remove accents, special chars)
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }

        return s.toLowerCase()
                .trim()
                .replaceAll("[àáâãäå]", "a")
                .replaceAll("[èéêë]", "e")
                .replaceAll("[ìíîï]", "i")
                .replaceAll("[òóôõö]", "o")
                .replaceAll("[ùúûü]", "u")
                .replaceAll("[ýÿ]", "y")
                .replaceAll("[ç]", "c")
                .replaceAll("[^a-z0-9\\s]", "");
    }
}
