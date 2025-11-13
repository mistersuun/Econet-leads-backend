package com.econet.leads.service;

import com.econet.leads.model.Business;
import com.econet.leads.util.PhoneFormatter;
import com.econet.leads.util.PostalCodeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DataQualityService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?://)?(www\\.)?[a-zA-Z0-9-]+(\\.[a-zA-Z]{2,})+(/.*)?$");

    /**
     * Calculate data quality score (0-100) for a business
     */
    public int calculateQualityScore(Business business) {
        int score = 0;

        // Name validation (15 points)
        if (isValidString(business.getBusinessName()) && business.getBusinessName().length() > 3) {
            score += 15;
        }

        // Phone validation (20 points)
        if (PhoneFormatter.isValid(business.getPhone())) {
            score += 20;
        }

        // Email validation (15 points)
        if (isValidEmail(business.getEmail())) {
            score += 15;
        }

        // Complete address (30 points total)
        if (isValidString(business.getAddressStreet())) {
            score += 10;
        }
        if (isValidString(business.getAddressCity())) {
            score += 10;
        }
        if (PostalCodeValidator.isValid(business.getPostalCode())) {
            score += 10;
        }

        // Geocoded (10 points)
        if (business.getLatitude() != null && business.getLongitude() != null) {
            score += 10;
        }

        // Website present (5 points)
        if (isValidUrl(business.getWebsite())) {
            score += 5;
        }

        // Recently verified (5 points)
        if (isRecentlyVerified(business.getLastVerified(), 30)) {
            score += 5;
        }

        return Math.min(score, 100);
    }

    /**
     * Check if string is valid (not null, not empty, not just whitespace)
     */
    private boolean isValidString(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Validate email format
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * Validate URL format
     */
    public boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        return URL_PATTERN.matcher(url).matches();
    }

    /**
     * Check if business was recently verified (within days)
     */
    public boolean isRecentlyVerified(LocalDateTime lastVerified, int days) {
        if (lastVerified == null) {
            return false;
        }

        long daysSinceVerification = ChronoUnit.DAYS.between(lastVerified, LocalDateTime.now());
        return daysSinceVerification <= days;
    }

    /**
     * Check if business data is stale (needs re-verification)
     */
    public boolean isStale(Business business, int staleDays) {
        if (business.getLastVerified() == null) {
            // If never verified, check creation date
            long daysSinceCreation = ChronoUnit.DAYS.between(business.getCreatedAt(), LocalDateTime.now());
            return daysSinceCreation > staleDays;
        }

        long daysSinceVerification = ChronoUnit.DAYS.between(business.getLastVerified(), LocalDateTime.now());
        return daysSinceVerification > staleDays;
    }

    /**
     * Generate quality report for a business
     */
    public String generateQualityReport(Business business) {
        StringBuilder report = new StringBuilder();
        report.append("Data Quality Report:\n");
        report.append("Score: ").append(calculateQualityScore(business)).append("/100\n\n");

        report.append("Issues:\n");
        if (!isValidString(business.getPhone()) || !PhoneFormatter.isValid(business.getPhone())) {
            report.append("- Missing or invalid phone number\n");
        }
        if (!isValidEmail(business.getEmail())) {
            report.append("- Missing or invalid email\n");
        }
        if (!isValidString(business.getAddressStreet())) {
            report.append("- Missing street address\n");
        }
        if (!PostalCodeValidator.isValid(business.getPostalCode())) {
            report.append("- Missing or invalid postal code\n");
        }
        if (business.getLatitude() == null || business.getLongitude() == null) {
            report.append("- Missing geocoding\n");
        }
        if (business.getLastVerified() == null) {
            report.append("- Never verified\n");
        } else if (isStale(business, 90)) {
            report.append("- Data is stale (>90 days old)\n");
        }

        return report.toString();
    }
}
