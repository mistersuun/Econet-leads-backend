package com.econet.leads.util;

import java.util.HashMap;
import java.util.Map;

public class AddressNormalizer {

    private static final Map<String, String> STREET_ABBREVIATIONS = new HashMap<>();

    static {
        // French abbreviations
        STREET_ABBREVIATIONS.put("rue", "Rue");
        STREET_ABBREVIATIONS.put("avenue", "Avenue");
        STREET_ABBREVIATIONS.put("av", "Avenue");
        STREET_ABBREVIATIONS.put("av.", "Avenue");
        STREET_ABBREVIATIONS.put("boulevard", "Boulevard");
        STREET_ABBREVIATIONS.put("boul", "Boulevard");
        STREET_ABBREVIATIONS.put("boul.", "Boulevard");
        STREET_ABBREVIATIONS.put("blvd", "Boulevard");
        STREET_ABBREVIATIONS.put("chemin", "Chemin");
        STREET_ABBREVIATIONS.put("ch", "Chemin");
        STREET_ABBREVIATIONS.put("ch.", "Chemin");
        STREET_ABBREVIATIONS.put("place", "Place");
        STREET_ABBREVIATIONS.put("pl", "Place");
        STREET_ABBREVIATIONS.put("pl.", "Place");

        // English abbreviations
        STREET_ABBREVIATIONS.put("street", "Street");
        STREET_ABBREVIATIONS.put("st", "Street");
        STREET_ABBREVIATIONS.put("st.", "Street");
        STREET_ABBREVIATIONS.put("road", "Road");
        STREET_ABBREVIATIONS.put("rd", "Road");
        STREET_ABBREVIATIONS.put("rd.", "Road");
        STREET_ABBREVIATIONS.put("drive", "Drive");
        STREET_ABBREVIATIONS.put("dr", "Drive");
        STREET_ABBREVIATIONS.put("dr.", "Drive");
    }

    /**
     * Normalize street address
     */
    public static String normalizeStreet(String street) {
        if (street == null || street.isEmpty()) {
            return null;
        }

        String normalized = street.trim();

        // Expand common abbreviations
        for (Map.Entry<String, String> entry : STREET_ABBREVIATIONS.entrySet()) {
            normalized = normalized.replaceAll("(?i)\\b" + entry.getKey() + "\\b", entry.getValue());
        }

        return normalized;
    }

    /**
     * Normalize city name
     */
    public static String normalizeCity(String city) {
        if (city == null || city.isEmpty()) {
            return null;
        }

        // Capitalize first letter of each word
        String[] words = city.trim().toLowerCase().split("\\s+");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1))
                      .append(" ");
            }
        }

        return result.toString().trim();
    }

    /**
     * Build full normalized address
     */
    public static String normalizeFullAddress(String street, String city, String province, String postalCode) {
        StringBuilder address = new StringBuilder();

        if (street != null && !street.isEmpty()) {
            address.append(normalizeStreet(street));
        }

        if (city != null && !city.isEmpty()) {
            if (address.length() > 0) address.append(", ");
            address.append(normalizeCity(city));
        }

        if (province != null && !province.isEmpty()) {
            if (address.length() > 0) address.append(", ");
            address.append(province.toUpperCase());
        }

        if (postalCode != null && !postalCode.isEmpty()) {
            if (address.length() > 0) address.append(" ");
            address.append(PostalCodeValidator.format(postalCode));
        }

        return address.toString();
    }
}
