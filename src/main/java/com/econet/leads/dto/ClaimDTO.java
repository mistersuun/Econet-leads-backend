package com.econet.leads.dto;

import com.econet.leads.realtime.ClaimRegistry;

import java.time.Instant;
import java.util.UUID;

/** Response of POST /api/leads/{id}/claim. */
public record ClaimDTO(UUID leadId, UUID userId, String username, Instant expiresAt) {

    public static ClaimDTO from(ClaimRegistry.Claim claim) {
        return new ClaimDTO(claim.leadId(), claim.userId(), claim.username(), claim.expiresAt());
    }
}
