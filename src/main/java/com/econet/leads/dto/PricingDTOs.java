package com.econet.leads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Shapes of GET /api/pricing and POST /api/pricing/estimate (mirroring the website's pricing.ts). */
public final class PricingDTOs {

    private PricingDTOs() {
    }

    public record AddOn(String id, int price, String labelFr, String labelEn) {
    }

    public record Tables(Map<String, Integer> servicePrices,
                         List<AddOn> addOns,
                         /** id -> price, the ADD_ONS table as the website keys it */
                         Map<String, Integer> addOnPrices,
                         Map<String, Double> frequencyDiscounts,
                         @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, Double> areaMultipliers,
                         Map<String, Double> propertyTypeMultipliers,
                         double taxRate,
                         List<String> notBookable) {
    }

    /** {service, area, propertyType, addOnIds, frequency} — the website's EstimateInput. */
    public record EstimateInput(String service, String area, String propertyType, List<String> addOnIds, String frequency) {
    }

    public record EstimateLine(String id, String labelFr, String labelEn, int price) {
    }

    /** Amounts are null when requiresQuote (area over_2500). */
    public record Estimate(String service,
                           int basePrice,
                           String area,
                           Double areaMultiplier,
                           String propertyType,
                           double propertyTypeMultiplier,
                           String frequency,
                           double discountRate,
                           List<EstimateLine> addOns,
                           int addOnsTotal,
                           Double servicePrice,
                           Long discountAmount,
                           Long subtotal,
                           Long taxes,
                           Long total,
                           String currency,
                           boolean requiresQuote) {
    }
}
