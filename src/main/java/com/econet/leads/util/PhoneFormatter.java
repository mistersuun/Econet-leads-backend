package com.econet.leads.util;

import java.util.regex.Pattern;

public class PhoneFormatter {

    private static final Pattern DIGITS_ONLY = Pattern.compile("[^0-9]");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\d{10}$");

    /**
     * Format phone number to (XXX) XXX-XXXX format
     */
    public static String format(String phone) {
        if (phone == null || phone.isEmpty()) {
            return null;
        }

        // Remove all non-digit characters
        String digits = DIGITS_ONLY.matcher(phone).replaceAll("");

        // Handle 11-digit numbers starting with 1 (remove leading 1)
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }

        // Validate 10-digit format
        if (!PHONE_PATTERN.matcher(digits).matches()) {
            return phone; // Return original if can't format
        }

        // Format as (XXX) XXX-XXXX
        return String.format("(%s) %s-%s",
                digits.substring(0, 3),
                digits.substring(3, 6),
                digits.substring(6, 10));
    }

    /**
     * Validate phone number format
     */
    public static boolean isValid(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }

        String digits = DIGITS_ONLY.matcher(phone).replaceAll("");

        // Handle 11-digit numbers starting with 1
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }

        return PHONE_PATTERN.matcher(digits).matches();
    }

    /**
     * Normalize phone number to digits only (for comparison)
     */
    public static String normalize(String phone) {
        if (phone == null || phone.isEmpty()) {
            return null;
        }

        String digits = DIGITS_ONLY.matcher(phone).replaceAll("");

        // Handle 11-digit numbers starting with 1
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }

        return digits;
    }
}
