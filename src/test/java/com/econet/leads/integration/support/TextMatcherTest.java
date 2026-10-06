package com.econet.leads.integration.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextMatcherTest {

    private final TextMatcher matcher = new TextMatcher(List.of(
            "nettoyage", "entretien ménager", "conciergerie", "janitorial", "custodial", "cleaning", "housekeeping"));

    @Test
    void isAccentAndCaseInsensitive() {
        assertThat(matcher.matches("ENTRETIEN MENAGER DES BUREAUX")).containsExactly("entretien ménager");
        assertThat(matcher.matches("Services d'entretien  ménager")).containsExactly("entretien ménager");
        assertThat(matcher.matches("Entretien-Ménager")).containsExactly("entretien ménager");
        assertThat(matcher.matches("NETTOYAGE")).containsExactly("nettoyage");
    }

    @Test
    void matchesAtWordStartOnly() {
        assertThat(matcher.matches("Travaux de nettoyages divers")).containsExactly("nettoyage");
        assertThat(matcher.matches("Drycleaning of uniforms")).isEmpty();
        assertThat(matcher.matches("Window cleaning; janitorial")).containsExactly("janitorial", "cleaning");
    }

    @Test
    void searchesAllTextsAndIgnoresNulls() {
        assertThat(matcher.matches(null, "Road repair", "Services de conciergerie")).containsExactly("conciergerie");
        assertThat(matcher.matchesAny((String) null)).isFalse();
    }

    @Test
    void normalizeStripsAccentsAndPunctuation() {
        assertThat(TextMatcher.normalize("L'Île-Bizard—Sainte-Geneviève")).isEqualTo("l ile bizard sainte genevieve");
    }
}
