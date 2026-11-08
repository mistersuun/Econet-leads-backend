package com.econet.leads.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Element of GET /api/users (ADMIN). */
public record UserSummaryDTO(UUID id, String username, String email, String role, boolean active,
                             int callTarget, int conversationTarget, LocalDateTime createdAt) {
}
