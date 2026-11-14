package com.econet.leads.service;

import java.util.Map;

/** Bilingual labels of the estimate inputs, for the quote PDF, the public pages and the email. */
public final class QuoteLabels {

    private QuoteLabels() {
    }

    private static final Map<String, String> AREA_FR = Map.of(
            "under_800", "Moins de 800 pi²", "800_1500", "800 à 1 500 pi²",
            "1500_2500", "1 500 à 2 500 pi²", "over_2500", "Plus de 2 500 pi²");
    private static final Map<String, String> AREA_EN = Map.of(
            "under_800", "Under 800 sq ft", "800_1500", "800 to 1,500 sq ft",
            "1500_2500", "1,500 to 2,500 sq ft", "over_2500", "Over 2,500 sq ft");
    private static final Map<String, String> TYPE_FR = Map.of(
            "apartment", "Appartement", "house", "Maison", "townhouse", "Maison de ville",
            "office", "Bureau", "retail", "Commerce de détail");
    private static final Map<String, String> TYPE_EN = Map.of(
            "apartment", "Apartment", "house", "House", "townhouse", "Townhouse",
            "office", "Office", "retail", "Retail");
    private static final Map<String, String> FREQ_FR = Map.of(
            "one-time", "Une fois", "monthly", "Mensuelle", "bi-weekly", "Aux deux semaines", "weekly", "Hebdomadaire");
    private static final Map<String, String> FREQ_EN = Map.of(
            "one-time", "One time", "monthly", "Monthly", "bi-weekly", "Every two weeks", "weekly", "Weekly");

    public static String service(String id, boolean en) {
        Map<String, String> m = en ? PricingService.SERVICE_LABELS_EN : PricingService.SERVICE_LABELS_FR;
        return m.getOrDefault(id, id);
    }

    public static String area(String id, boolean en) {
        return (en ? AREA_EN : AREA_FR).getOrDefault(id, id);
    }

    public static String propertyType(String id, boolean en) {
        return (en ? TYPE_EN : TYPE_FR).getOrDefault(id, id);
    }

    public static String frequency(String id, boolean en) {
        return (en ? FREQ_EN : FREQ_FR).getOrDefault(id, id);
    }
}
