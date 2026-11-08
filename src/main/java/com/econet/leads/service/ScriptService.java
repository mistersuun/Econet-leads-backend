package com.econet.leads.service;

import com.econet.leads.dto.ScriptDTOs;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.CallScript;
import com.econet.leads.model.ScriptSection;
import com.econet.leads.repository.CallScriptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Call scripts: best match by business type + language with fallback to the language default. */
@Service
@RequiredArgsConstructor
public class ScriptService {

    public static final Set<String> SECTION_KEYS = Set.of("opening", "questions", "objections", "closing");
    public static final Set<String> LANGUAGES = Set.of("fr", "en");

    private final CallScriptRepository repository;

    /**
     * Exact (businessType, language), else the type matched case-insensitively, else the default
     * script of the language (fallback = true). 404 when the language has no default either.
     */
    @Transactional(readOnly = true)
    public ScriptDTOs.Script bestMatch(String businessType, String language) {
        String lang = normalizeLanguage(language);
        String type = businessType == null ? null : businessType.trim();
        if (type != null && !type.isEmpty()) {
            List<CallScript> exact = repository.findByActiveTrueAndLanguageAndBusinessTypeOrderByUpdatedAtDesc(lang, type);
            if (!exact.isEmpty()) {
                return ScriptDTOs.Script.from(exact.get(0), false);
            }
            for (CallScript s : repository.findAllByOrderByLanguageAscBusinessTypeAscNameAsc()) {
                if (Boolean.TRUE.equals(s.getActive()) && lang.equals(s.getLanguage())
                        && s.getBusinessType() != null && s.getBusinessType().equalsIgnoreCase(type)) {
                    return ScriptDTOs.Script.from(s, false);
                }
            }
        }
        List<CallScript> defaults = repository.findByActiveTrueAndLanguageAndBusinessTypeIsNullOrderByUpdatedAtDesc(lang);
        if (defaults.isEmpty()) {
            throw ApiException.notFound("No call script for language " + lang);
        }
        return ScriptDTOs.Script.from(defaults.get(0), type != null && !type.isEmpty());
    }

    @Transactional(readOnly = true)
    public List<ScriptDTOs.Script> all() {
        return repository.findAllByOrderByLanguageAscBusinessTypeAscNameAsc().stream()
                .map(s -> ScriptDTOs.Script.from(s, false)).toList();
    }

    @Transactional
    public ScriptDTOs.Script create(ScriptDTOs.Upsert request) {
        CallScript script = new CallScript();
        apply(script, request);
        return ScriptDTOs.Script.from(repository.saveAndFlush(script), false);
    }

    @Transactional
    public ScriptDTOs.Script update(UUID id, ScriptDTOs.Upsert request) {
        CallScript script = repository.findById(id).orElseThrow(() -> ApiException.notFound("Script not found: " + id));
        apply(script, request);
        return ScriptDTOs.Script.from(repository.saveAndFlush(script), false);
    }

    private static void apply(CallScript script, ScriptDTOs.Upsert request) {
        script.setName(request.getName().trim());
        script.setBusinessType(request.getBusinessType() == null || request.getBusinessType().isBlank()
                ? null : request.getBusinessType().trim());
        script.setLanguage(normalizeLanguage(request.getLanguage()));
        List<ScriptSection> sections = new ArrayList<>();
        for (ScriptSection s : request.getSections()) {
            if (s == null || s.getKey() == null || !SECTION_KEYS.contains(s.getKey())) {
                throw ApiException.badRequest("section key must be one of " + SECTION_KEYS);
            }
            if (s.getBody() == null) {
                throw ApiException.badRequest("section '" + s.getKey() + "' needs a body");
            }
            sections.add(new ScriptSection(s.getKey(), s.getTitle() == null ? s.getKey() : s.getTitle().trim(), s.getBody()));
        }
        script.setSections(sections);
        if (request.getActive() != null) {
            script.setActive(request.getActive());
        } else if (script.getActive() == null) {
            script.setActive(true);
        }
    }

    static String normalizeLanguage(String language) {
        String lang = language == null || language.isBlank() ? "fr" : language.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(lang)) {
            throw ApiException.badRequest("language must be fr or en");
        }
        return lang;
    }
}
