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
        @Min(1) @Max(500)
        private Integer callTarget;
        @Min(1) @Max(500)
        private Integer conversationTarget;
        @Min(1) @Max(500)
        private Integer dailyCallTarget;
        @Min(1) @Max(500)
        private Integer dailyConversationTarget;

        public Integer effectiveCallTarget() {
            return callTarget != null ? callTarget : dailyCallTarget;
        }

        public Integer effectiveConversationTarget() {
            return conversationTarget != null ? conversationTarget : dailyConversationTarget;
        }
    }
}
