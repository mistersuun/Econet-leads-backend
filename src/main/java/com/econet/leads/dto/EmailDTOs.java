package com.econet.leads.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Shapes of the email endpoints. */
public final class EmailDTOs {

    private EmailDTOs() {
    }

    /** GET /api/email/status */
    public record Status(boolean configured, String from, String fromName, int rateLimitPerHour) {
    }

    public record Template(UUID id, String key, String language, String subject, String body, boolean active,
                           LocalDateTime updatedAt) {
    }
}
