package com.econet.leads.integration.support;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accent- and case-insensitive keyword matching ("Entretien ménager" matches "ENTRETIEN MENAGER",
 * "entretien  ménager"). A keyword matches when it starts at a word boundary, so "nettoyage" also
 * matches "nettoyages" but "cleaning" does not match inside "drycleaning"... unless written so.
 */
public final class TextMatcher {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final List<String> keywords;
    private final List<Pattern> patterns;

    public TextMatcher(List<String> keywords) {
        this.keywords = new ArrayList<>();
        this.patterns = new ArrayList<>();
        for (String k : keywords) {
            String n = normalize(k);
            if (n.isEmpty()) continue;
            this.keywords.add(k);
            // word-start boundary; normalized text only contains [a-z0-9 ]
            this.patterns.add(Pattern.compile("(?:^| )" + Pattern.quote(n)));
        }
    }

    /** Lower-case, strip accents, collapse every non-alphanumeric run (incl. apostrophes, dashes) to one space. */
    public static String normalize(String text) {
        if (text == null) return "";
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        String noAccents = DIACRITICS.matcher(decomposed).replaceAll("");
        return NON_ALNUM.matcher(noAccents.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public boolean isEmpty() {
        return keywords.isEmpty();
    }

    /** Keywords (as configured) found in any of the texts, in configuration order, without duplicates. */
    public List<String> matches(String... texts) {
        StringBuilder sb = new StringBuilder();
        for (String t : texts) {
            if (t != null && !t.isBlank()) {
                sb.append(' ').append(normalize(t));
            }
        }
        String haystack = sb.toString().trim();
        List<String> found = new ArrayList<>();
        if (haystack.isEmpty()) return found;
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).matcher(haystack).find()) {
                found.add(keywords.get(i));
            }
        }
        return found;
    }

    public boolean matchesAny(String... texts) {
        return !matches(texts).isEmpty();
    }
}
