package com.econet.leads.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

public final class PasswordDTOs {

    private PasswordDTOs() {
    }

    /** Body of POST /api/me/password. */
    @Data
    public static class Change {
        @NotBlank(message = "Le mot de passe actuel est obligatoire")
        private String currentPassword;
        @NotBlank(message = "Le nouveau mot de passe est obligatoire")
        private String newPassword;
    }

    /** Body of PUT /api/users/{id}/password (ADMIN). */
    @Data
    public static class Reset {
        @NotBlank(message = "Le nouveau mot de passe est obligatoire")
        private String newPassword;
    }
}
