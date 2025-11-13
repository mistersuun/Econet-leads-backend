package com.econet.leads.util;

import java.util.regex.Pattern;

public class PostalCodeValidator {

    // Canadian postal code format: A1A 1A1
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[A-Z]\\d[A-Z]\\s?\\d[A-Z]\\d$", Pattern.CASE_INSENSITIVE);

    /**
     * Validate Canadian postal code
     */
    public static boolean isValid(String postalCode) {
        if (postalCode == null || postalCode.isEmpty()) {
            return false;
        }

        return POSTAL_CODE_PATTERN.matcher(postalCode.trim()).matches();
    }

    /**
     * Format postal code to standard format: A1A 1A1
     */
    public static String format(String postalCode) {
        if (postalCode == null || postalCode.isEmpty()) {
            return null;
        }

        // Remove all spaces
        String clean = postalCode.replaceAll("\\s", "").toUpperCase();

        // Validate length
        if (clean.length() != 6) {
            return postalCode; // Return original if can't format
        }

        // Format as A1A 1A1
        return String.format("%s %s", clean.substring(0, 3), clean.substring(3, 6));
    }

    /**
     * Normalize postal code (remove spaces, uppercase)
     */
    public static String normalize(String postalCode) {
        if (postalCode == null || postalCode.isEmpty()) {
            return null;
        }

        return postalCode.replaceAll("\\s", "").toUpperCase();
    }
}
