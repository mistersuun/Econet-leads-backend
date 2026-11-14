package com.econet.leads.sms;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

public final class SmsDTOs {

    private SmsDTOs() {
    }

    /** Body of POST /api/leads/{id}/sms. */
    @Data
    public static class FollowUpRequest {
        /** missed_call | after_voicemail | quote_followup */
        @NotBlank(message = "templateKey is required")
        private String templateKey;
        /** fr | en (default: the lead's language, else fr) */
        private String language;
        /** Must be true: the prospect agreed (verbally) to receive a text. */
        private Boolean consent;
    }

    public record FollowUpResponse(String status) {
    }

    public record Preview(String body) {
    }

    public record IntegrationsStatus(boolean smtp, boolean sms, boolean alertsPhone, boolean alertsEmail, boolean places,
                                     int placesUsedToday, int placesDailyLimit, boolean googleReviewUrl, boolean websiteDb,
                                     String publicUrl, String crmUrl) {
    }

    public record TestAlertResponse(boolean smsConfigured, int smsTargets, int smsSent, boolean emailConfigured,
                                    int emailTargets, int emailSent) {
    }
}
