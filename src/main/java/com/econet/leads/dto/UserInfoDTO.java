package com.econet.leads.dto;

import java.util.UUID;

/** Response of GET /api/auth/me */
public record UserInfoDTO(UUID userId, String username, String email, String role) {
}
