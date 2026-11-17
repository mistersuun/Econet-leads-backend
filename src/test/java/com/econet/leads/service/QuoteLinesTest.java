package com.econet.leads.service;

import com.econet.leads.dto.PricingDTOs.Estimate;
import com.econet.leads.dto.PricingDTOs.EstimateInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteLinesTest {

    final PricingService pricing = new PricingService();

    @Test
    void linesAddUpExactlyToTheWebsiteSubtotalEvenWhenRoundingDiffers() {
        // 120 x 1.25 x 1.15 = 172.50, + windows 30, weekly -15 %: the website rounds the subtotal to 172
        Estimate e = pricing.estimate(new EstimateInput("residential", "800_1500", "house", List.of("windows"), "weekly"));
        List<Map<String, Object>> lines = QuoteService.lines(e, false);
        double sum = lines.stream().mapToDouble(l -> ((Number) l.get("amount")).doubleValue()).sum();
        assertThat(sum).isEqualTo((double) e.subtotal());
        assertThat(lines.get(lines.size() - 1).get("id")).isEqualTo("discount");
        assertThat((String) lines.get(lines.size() - 1).get("label")).contains("Rabais").contains("15 %");
        assertThat(QuoteService.lines(e, true).get(1).get("label")).isEqualTo("+ Windows");
    }

    @Test
    void noDiscountLineForOneTimeServices() {
        Estimate e = pricing.estimate(new EstimateInput("commercial", "under_800", "office", null, "one-time"));
        assertThat(QuoteService.lines(e, false)).hasSize(1);
    }

    @Test
    void tokensAreUnguessableShapedAndComparedExactly() {
        Set<String> tokens = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) tokens.add(PublicLinkService.newToken());
        assertThat(tokens).hasSize(200);
        String t = tokens.iterator().next();
        assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(PublicLinkService.looksLikeToken(t)).isTrue();
        assertThat(PublicLinkService.looksLikeToken(t + "x")).isFalse();
        assertThat(PublicLinkService.looksLikeToken("../etc/passwd")).isFalse();
        assertThat(PublicLinkService.secretsEqual(t, t)).isTrue();
        assertThat(PublicLinkService.secretsEqual(t, t.substring(0, 42) + (t.endsWith("A") ? "B" : "A"))).isFalse();
        assertThat(PublicLinkService.secretsEqual(null, t)).isFalse();
        assertThat(tokens.stream().collect(Collectors.joining()).length()).isEqualTo(200 * 43);
    }
}
