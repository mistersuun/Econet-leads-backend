package com.econet.leads.integration.osm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovery categories: their Overpass tag filters, their matching rule on element tags and their French business type. */
public final class OsmCategories {

    public record Category(String key, String businessType, List<String> overpassFilters,
                           Map<String, Set<String>> tagValues, Set<String> anyValueKeys, Map<String, Set<String>> requiredTags) {

        /** The "key=value" tag of the element that put it in this category, or null when it does not belong. */
        String matchTag(Map<String, String> tags) {
            for (Map.Entry<String, Set<String>> e : tagValues.entrySet()) {
                String v = tags.get(e.getKey());
                if (v != null && e.getValue().contains(v) && requiredOk(tags)) {
                    return e.getKey() + "=" + v;
                }
            }
            for (String key : anyValueKeys) {
                if (tags.get(key) != null && requiredOk(tags)) {
                    return key + "=" + tags.get(key);
                }
            }
            return null;
        }

        private boolean requiredOk(Map<String, String> tags) {
            for (Map.Entry<String, Set<String>> e : requiredTags.entrySet()) {
                String v = tags.get(e.getKey());
                if (v == null || !e.getValue().contains(v)) return false;
            }
            return true;
        }
    }

    public static final Map<String, Category> ALL = new LinkedHashMap<>();

    static {
        add("restaurant", "Restaurant", List.of("[\"amenity\"~\"^(restaurant|cafe|fast_food|bar)$\"]"),
                Map.of("amenity", Set.of("restaurant", "cafe", "fast_food", "bar")), Set.of(), Map.of());
        add("clinic", "Clinique", List.of("[\"amenity\"~\"^(clinic|doctors|dentist)$\"]", "[\"healthcare\"]"),
                Map.of("amenity", Set.of("clinic", "doctors", "dentist")), Set.of("healthcare"), Map.of());
        add("daycare", "Garderie", List.of("[\"amenity\"~\"^(kindergarten|childcare)$\"]"),
                Map.of("amenity", Set.of("kindergarten", "childcare")), Set.of(), Map.of());
        add("office", "Bureau", List.of("[\"office\"]"), Map.of(), Set.of("office"), Map.of());
        add("retail", "Commerce", List.of("[\"shop\"]"), Map.of(), Set.of("shop"), Map.of());
        add("gym", "Gym", List.of("[\"leisure\"~\"^(fitness_centre|sports_centre)$\"]"),
                Map.of("leisure", Set.of("fitness_centre", "sports_centre")), Set.of(), Map.of());
        add("school", "École", List.of("[\"amenity\"~\"^(school|college)$\"]"),
                Map.of("amenity", Set.of("school", "college")), Set.of(), Map.of());
        add("hotel", "Hôtel", List.of("[\"tourism\"~\"^(hotel|motel|guest_house)$\"]"),
                Map.of("tourism", Set.of("hotel", "motel", "guest_house")), Set.of(), Map.of());
        add("senior", "Résidence", List.of("[\"amenity\"=\"social_facility\"][\"social_facility\"~\"^(nursing_home|assisted_living)$\"]"),
                Map.of("amenity", Set.of("social_facility")), Set.of(),
                Map.of("social_facility", Set.of("nursing_home", "assisted_living")));
        add("pharmacy", "Pharmacie", List.of("[\"amenity\"=\"pharmacy\"]"),
                Map.of("amenity", Set.of("pharmacy")), Set.of(), Map.of());
    }

    private OsmCategories() {
    }

    private static void add(String key, String type, List<String> filters, Map<String, Set<String>> values,
                            Set<String> anyValue, Map<String, Set<String>> required) {
        ALL.put(key, new Category(key, type, filters, values, anyValue, required));
    }

    /** French label of a category key, or "Autre". */
    public static String businessType(String key) {
        Category c = key == null ? null : ALL.get(key);
        return c != null ? c.businessType() : "Autre";
    }
}
