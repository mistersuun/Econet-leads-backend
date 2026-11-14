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
        @NotBlank(message = "Le nom est obligatoire")
        @Size(max = 200, message = "Le nom ne doit pas dépasser 200 caractères")
        private String name;
        /** null / blank = default script for the language */
        @Size(max = 100, message = "Le type d'entreprise ne doit pas dépasser 100 caractères")
        private String businessType;
        @NotBlank(message = "La langue est obligatoire")
        private String language;
        @NotEmpty(message = "Les sections sont obligatoires")
        private List<ScriptSection> sections;
        private Boolean active;
    }
}
