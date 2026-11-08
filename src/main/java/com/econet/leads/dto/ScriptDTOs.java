package com.econet.leads.dto;

import com.econet.leads.model.CallScript;
import com.econet.leads.model.ScriptSection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Shapes of /api/scripts. */
public final class ScriptDTOs {

    private ScriptDTOs() {
    }

    public record Script(UUID id, String name, String businessType, String language, List<ScriptSection> sections,
                         boolean active, LocalDateTime updatedAt, boolean fallback) {

        public static Script from(CallScript s, boolean fallback) {
            return new Script(s.getId(), s.getName(), s.getBusinessType(), s.getLanguage(), s.getSections(),
                    Boolean.TRUE.equals(s.getActive()), s.getUpdatedAt(), fallback);
        }
    }

    /** Body of POST /api/scripts and PUT /api/scripts/{id}. */
    @Data
    public static class Upsert {
        @NotBlank(message = "name is required")
        @Size(max = 200)
        private String name;
        /** null / blank = default script for the language */
        @Size(max = 100)
        private String businessType;
        @NotBlank(message = "language is required")
        private String language;
        @NotEmpty(message = "sections are required")
        private List<ScriptSection> sections;
        private Boolean active;
    }
}
