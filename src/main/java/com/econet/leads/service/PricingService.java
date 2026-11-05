package com.econet.leads.service;

import com.econet.leads.dto.PricingDTOs.AddOn;
import com.econet.leads.dto.PricingDTOs.Estimate;
import com.econet.leads.dto.PricingDTOs.EstimateInput;
import com.econet.leads.dto.PricingDTOs.EstimateLine;
import com.econet.leads.dto.PricingDTOs.Tables;
import com.econet.leads.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirror of the website's src/app/shared/pricing.ts (SERVICE_PRICES, ADD_ONS, FREQUENCY_DISCOUNTS,
 * AREA_MULTIPLIERS, PROPERTY_TYPE_MULTIPLIERS, QC_TAX_RATE, estimate()). Keep the two in sync.
 *
 * subtotal = round((base * area * propertyType + addOns) * (1 - discount)); taxes = round(subtotal * 0.14975).
 */
@Service
public class PricingService {

    public static final double QC_TAX_RATE = 0.14975;

    /** Service id -> base price (CAD). post_construction is listed but not bookable online. */
    public static final Map<String, Integer> SERVICE_PRICES = ordered(
            "residential", 120, "commercial", 200, "deep_cleaning", 280, "post_construction", 350);
    public static final List<String> NOT_BOOKABLE = List.of("post_construction");

    public static final List<AddOn> ADD_ONS = List.of(
            new AddOn("windows", 30, "Fenêtres", "Windows"),
            new AddOn("oven", 25, "Four", "Oven"),
            new AddOn("fridge", 35, "Réfrigérateur", "Fridge"),
            new AddOn("basement", 50, "Sous-sol", "Basement"),
            new AddOn("garage", 40, "Garage", "Garage"));

    public static final Map<String, Double> FREQUENCY_DISCOUNTS = ordered(
            "one-time", 0.0, "monthly", 0.05, "bi-weekly", 0.10, "weekly", 0.15);

    /** Area bucket -> multiplier; null = custom quote. */
    public static final Map<String, Double> AREA_MULTIPLIERS = orderedNullable(
            "under_800", 1.0, "800_1500", 1.25, "1500_2500", 1.5, "over_2500", null);

    public static final Map<String, Double> PROPERTY_TYPE_MULTIPLIERS = ordered(
            "apartment", 1.0, "house", 1.15, "townhouse", 1.15, "office", 1.0, "retail", 1.0);

    public static final Map<String, String> SERVICE_LABELS_FR = ordered(
            "residential", "Entretien résidentiel", "commercial", "Entretien commercial",
            "deep_cleaning", "Grand ménage", "post_construction", "Nettoyage après construction");
    public static final Map<String, String> SERVICE_LABELS_EN = ordered(
            "residential", "Residential cleaning", "commercial", "Commercial cleaning",
            "deep_cleaning", "Deep cleaning", "post_construction", "Post-construction cleaning");

    public Tables tables() {
        Map<String, Integer> addOns = new LinkedHashMap<>();
        ADD_ONS.forEach(a -> addOns.put(a.id(), a.price()));
        return new Tables(SERVICE_PRICES, ADD_ONS, addOns, FREQUENCY_DISCOUNTS, AREA_MULTIPLIERS,
                PROPERTY_TYPE_MULTIPLIERS, QC_TAX_RATE, NOT_BOOKABLE);
    }

    /**
     * Itemised estimate. Inputs are validated (400). An area of over_2500 cannot be priced online:
     * the estimate comes back with requiresQuote = true and null amounts.
     */
    public Estimate estimate(EstimateInput input) {
        if (input == null) {
            throw ApiException.badRequest("estimate body is required");
        }
        String service = require(input.service(), "service");
        Integer base = SERVICE_PRICES.get(service);
        if (base == null) {
            throw ApiException.badRequest("service must be one of " + SERVICE_PRICES.keySet());
        }
        if (NOT_BOOKABLE.contains(service)) {
            throw ApiException.badRequest("service '" + service + "' is not bookable online: it requires a custom quote");
        }
        String area = require(input.area(), "area");
        if (!AREA_MULTIPLIERS.containsKey(area)) {
            throw ApiException.badRequest("area must be one of " + AREA_MULTIPLIERS.keySet());
        }
        String propertyType = require(input.propertyType(), "propertyType");
        Double typeMultiplier = PROPERTY_TYPE_MULTIPLIERS.get(propertyType);
        if (typeMultiplier == null) {
            throw ApiException.badRequest("propertyType must be one of " + PROPERTY_TYPE_MULTIPLIERS.keySet());
        }
        String frequency = input.frequency() == null || input.frequency().isBlank() ? "one-time" : input.frequency().trim();
        Double discount = FREQUENCY_DISCOUNTS.get(frequency);
        if (discount == null) {
            throw ApiException.badRequest("frequency must be one of " + FREQUENCY_DISCOUNTS.keySet());
        }
        List<EstimateLine> addOnLines = new ArrayList<>();
        int addOnsTotal = 0;
        if (input.addOnIds() != null) {
            for (String id : input.addOnIds()) {
                AddOn addOn = ADD_ONS.stream().filter(a -> a.id().equals(id)).findFirst()
                        .orElseThrow(() -> ApiException.badRequest("Unknown add-on '" + id + "'"));
                if (addOnLines.stream().noneMatch(l -> l.id().equals(id))) {
                    addOnLines.add(new EstimateLine(addOn.id(), addOn.labelFr(), addOn.labelEn(), addOn.price()));
                    addOnsTotal += addOn.price();
                }
            }
        }

        Double areaMultiplier = AREA_MULTIPLIERS.get(area);
        if (areaMultiplier == null) {
            return new Estimate(service, base, area, null, propertyType, typeMultiplier, frequency, discount,
                    addOnLines, addOnsTotal, null, null, null, null, null, "CAD", true);
        }
        double servicePrice = base * areaMultiplier * typeMultiplier;
        double beforeDiscount = servicePrice + addOnsTotal;
        long subtotal = Math.round(beforeDiscount * (1 - discount));
        long discountAmount = Math.round(beforeDiscount) - subtotal;
        long taxes = Math.round(subtotal * QC_TAX_RATE);
        return new Estimate(service, base, area, areaMultiplier, propertyType, typeMultiplier, frequency, discount,
                addOnLines, addOnsTotal, round2(servicePrice), discountAmount, subtotal, taxes, subtotal + taxes,
                "CAD", false);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " is required");
        }
        return value.trim();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> ordered(Object... kv) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (V) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private static Map<String, Double> orderedNullable(Object... kv) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (Double) kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
