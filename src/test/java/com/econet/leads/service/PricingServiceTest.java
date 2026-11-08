package com.econet.leads.service;

import com.econet.leads.dto.PricingDTOs.Estimate;
import com.econet.leads.dto.PricingDTOs.EstimateInput;
import com.econet.leads.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reproduces the website's pricing.ts estimate() on its reference cases. */
class PricingServiceTest {

    private final PricingService service = new PricingService();

    @Test
    void residentialApartmentOneTimeNoAddOns() {
        Estimate e = service.estimate(new EstimateInput("residential", "under_800", "apartment", List.of(), "one-time"));
        assertThat(e.subtotal()).isEqualTo(120);
        assertThat(e.taxes()).isEqualTo(18);
        assertThat(e.total()).isEqualTo(138);
        assertThat(e.requiresQuote()).isFalse();
        assertThat(e.currency()).isEqualTo("CAD");
    }

    @Test
    void residentialHouseWeeklyWithOvenAndFridge() {
        Estimate e = service.estimate(new EstimateInput("residential", "1500_2500", "house", List.of("oven", "fridge"), "weekly"));
        assertThat(e.servicePrice()).isEqualTo(207.0);
        assertThat(e.addOnsTotal()).isEqualTo(60);
        assertThat(e.discountRate()).isEqualTo(0.15);
        assertThat(e.subtotal()).isEqualTo(227);
        assertThat(e.taxes()).isEqualTo(34);
        assertThat(e.total()).isEqualTo(261);
        assertThat(e.addOns()).extracting("id").containsExactly("oven", "fridge");
    }

    @Test
    void over2500RequiresQuote() {
        Estimate e = service.estimate(new EstimateInput("commercial", "over_2500", "office", null, "monthly"));
        assertThat(e.requiresQuote()).isTrue();
        assertThat(e.total()).isNull();
    }

    @Test
    void frequencyDefaultsToOneTimeAndInputsAreValidated() {
        assertThat(service.estimate(new EstimateInput("commercial", "800_1500", "retail", null, null)).subtotal()).isEqualTo(250);
        assertThatThrownBy(() -> service.estimate(new EstimateInput("post_construction", "under_800", "house", null, "weekly")))
                .isInstanceOf(ApiException.class).hasMessageContaining("not bookable");
        assertThatThrownBy(() -> service.estimate(new EstimateInput("residential", "huge", "house", null, "weekly")))
                .isInstanceOf(ApiException.class).hasMessageContaining("area");
        assertThatThrownBy(() -> service.estimate(new EstimateInput("residential", "under_800", "house", List.of("pool"), "weekly")))
                .isInstanceOf(ApiException.class).hasMessageContaining("pool");
    }

    @Test
    void tablesMirrorTheWebsite() {
        var t = service.tables();
        assertThat(t.servicePrices()).containsEntry("residential", 120).containsEntry("commercial", 200)
                .containsEntry("deep_cleaning", 280).containsEntry("post_construction", 350);
        assertThat(t.addOnPrices()).containsEntry("windows", 30).containsEntry("oven", 25).containsEntry("fridge", 35)
                .containsEntry("basement", 50).containsEntry("garage", 40);
        assertThat(t.frequencyDiscounts()).containsEntry("bi-weekly", 0.10).containsEntry("weekly", 0.15);
        assertThat(t.areaMultipliers()).containsEntry("1500_2500", 1.5).containsEntry("over_2500", null);
        assertThat(t.propertyTypeMultipliers()).containsEntry("townhouse", 1.15).containsEntry("retail", 1.0);
        assertThat(t.taxRate()).isEqualTo(0.14975);
    }
}
