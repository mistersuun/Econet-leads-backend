package com.econet.leads.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** GET/PUT /api/me/targets and PUT /api/users/{id}/targets. */
public final class TargetsDTO {

    private TargetsDTO() {
    }

    public record Targets(int callTarget, int conversationTarget) {
    }

    /** Fields left null are unchanged; dailyCallTarget / dailyConversationTarget are accepted as aliases. */
    @Data
    public static class Update {
        @Min(value = 1, message = "La cible doit être entre 1 et 500") @Max(value = 500, message = "La cible doit être entre 1 et 500")
        private Integer callTarget;
        @Min(value = 1, message = "La cible doit être entre 1 et 500") @Max(value = 500, message = "La cible doit être entre 1 et 500")
        private Integer conversationTarget;
        @Min(value = 1, message = "La cible doit être entre 1 et 500") @Max(value = 500, message = "La cible doit être entre 1 et 500")
        private Integer dailyCallTarget;
        @Min(value = 1, message = "La cible doit être entre 1 et 500") @Max(value = 500, message = "La cible doit être entre 1 et 500")
        private Integer dailyConversationTarget;

        public Integer effectiveCallTarget() {
            return callTarget != null ? callTarget : dailyCallTarget;
        }

        public Integer effectiveConversationTarget() {
            return conversationTarget != null ? conversationTarget : dailyConversationTarget;
        }
    }
}
