package com.econet.leads.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One section of a call script, stored in call_scripts.sections (JSONB).
 * key is one of opening | questions | objections | closing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScriptSection {
    private String key;
    private String title;
    private String body;
}
