package com.econet.leads.integration.osm;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.DiscoverDTOs.Candidate;
import com.econet.leads.dto.DiscoverDTOs.ImportResponse;
import com.econet.leads.dto.DiscoverDTOs.SearchResponse;
import com.econet.leads.exception.ApiException;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Business;
import com.econet.leads.model.User;
import com.econet.leads.service.BusinessService;
import com.econet.leads.service.PriorityScoreService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** "Trouver des entreprises": Overpass search by city and category, and import of the chosen candidates. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OsmDiscoveryService {

    public static final String DATA_SOURCE = "OpenStreetMap";
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 300;

    private final OverpassClient client;
    private final BusinessService businessService;
    private final PriorityScoreService priorityScoreService;

    // ------------------------------------------------------------------ search

    public SearchResponse search(String city, List<String> categories, Integer limit) {
        String cleanCity = city == null ? "" : city.strip();
        if (cleanCity.isEmpty() || cleanCity.length() > 100 || cleanCity.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("city est requis (100 caractères maximum)");
        }
        if (categories == null || categories.isEmpty()) {
            throw ApiException.badRequest("categories est requis : " + String.join(", ", OsmCategories.ALL.keySet()));
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String c : categories) {
            String key = c == null ? "" : c.strip().toLowerCase(Locale.ROOT);
            if (!OsmCategories.ALL.containsKey(key)) {
                throw ApiException.badRequest("Catégorie inconnue '" + c + "' (attendu : " + String.join(", ", OsmCategories.ALL.keySet()) + ")");
            }
            keys.add(key);
        }
        int max = limit == null ? DEFAULT_LIMIT : limit;
        if (max < 1) {
            throw ApiException.badRequest("limit doit être au moins 1");
        }
        max = Math.min(max, MAX_LIMIT);

        List<JsonNode> elements = client.run(areaQuery(cleanCity, keys, max));
        if (elements.isEmpty()) {
            // Name-based area not found (or empty): fall back to the place's bounding box from Nominatim
            var box = client.geocode(cleanCity + ", Québec, Canada");
            if (box.isPresent()) {
                elements = client.run(bboxQuery(box.get(), keys, max));
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode element : elements) {
            Candidate candidate = toCandidate(element, keys, cleanCity);
            if (candidate != null && seen.add(candidate.osmId())) {
                candidates.add(withExisting(candidate));
                if (candidates.size() >= max) break;
            }
        }
        return new SearchResponse(candidates, com.econet.leads.dto.DiscoverDTOs.ATTRIBUTION);
    }

    // ------------------------------------------------------------------ queries

    static String areaQuery(String city, Set<String> categories, int limit) {
        StringBuilder q = new StringBuilder("[out:json][timeout:25];\n")
                .append("area[\"name\"=\"").append(escape(city)).append("\"][\"boundary\"=\"administrative\"]->.a;\n(\n");
        appendFilters(q, categories, "(area.a)");
        return q.append(");\nout center tags ").append(limit).append(";").toString();
    }

    static String bboxQuery(OverpassClient.BoundingBox b, Set<String> categories, int limit) {
        StringBuilder q = new StringBuilder("[out:json][timeout:25];\n(\n");
        appendFilters(q, categories, String.format(Locale.ROOT, "(%.6f,%.6f,%.6f,%.6f)", b.south(), b.west(), b.north(), b.east()));
        return q.append(");\nout center tags ").append(limit).append(";").toString();
    }

    private static void appendFilters(StringBuilder q, Set<String> categories, String scope) {
        for (String key : categories) {
            for (String filter : OsmCategories.ALL.get(key).overpassFilters()) {
                q.append("  node").append(filter).append(scope).append(";\n");
                q.append("  way").append(filter).append(scope).append(";\n");
            }
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------ mapping

    static Candidate toCandidate(JsonNode element, Set<String> categories, String searchedCity) {
        Map<String, String> tags = new LinkedHashMap<>();
        element.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        String name = blankToNull(tags.get("name"));
        String type = element.path("type").asText();
        if (name == null || element.path("id").isMissingNode() || type.isEmpty()) {
            return null; // unnamed places cannot become leads
        }
        double lat = element.has("lat") ? element.path("lat").asDouble() : element.path("center").path("lat").asDouble(Double.NaN);
        double lon = element.has("lon") ? element.path("lon").asDouble() : element.path("center").path("lon").asDouble(Double.NaN);

        String category = null;
        String tag = null;
        for (String key : categories) {
            tag = OsmCategories.ALL.get(key).matchTag(tags);
            if (tag != null) {
                category = key;
                break;
            }
        }
        if (category == null) {
            category = categories.iterator().next();
        }

        String street = blankToNull(tags.get("addr:street"));
        String number = blankToNull(tags.get("addr:housenumber"));
        String addressStreet = street == null ? null : (number == null ? street : number + " " + street);
        String city = firstNonBlank(tags.get("addr:city"), searchedCity);

        return new Candidate(type + "/" + element.path("id").asLong(), name, category,
                firstValue(firstNonBlank(tags.get("contact:phone"), tags.get("phone"))),
                firstValue(firstNonBlank(tags.get("contact:website"), tags.get("website"))),
                firstValue(firstNonBlank(tags.get("contact:email"), tags.get("email"))),
                addressStreet, city, blankToNull(tags.get("addr:postcode")),
                Double.isNaN(lat) ? null : coordinate(lat), Double.isNaN(lon) ? null : coordinate(lon), null, tag);
    }

    private Candidate withExisting(Candidate c) {
        var existing = businessService.findExistingLeadId(c.name(), c.phone(), c.addressCity());
        return existing.isEmpty() ? c : new Candidate(c.osmId(), c.name(), c.category(), c.phone(), c.website(), c.email(),
                c.addressStreet(), c.addressCity(), c.postalCode(), c.lat(), c.lon(), existing.get(), c.osmTag());
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports the chosen candidates as leads (dataSource "OpenStreetMap", externalId = osmId). A
     * candidate that matches an existing lead (re-checked here, the client flag is not trusted) or was
     * imported before is skipped untouched.
     */
    public ImportResponse importCandidates(List<Candidate> candidates, boolean assignToMe, User caller) {
        if (candidates == null || candidates.isEmpty()) {
            throw ApiException.badRequest("candidates est requis");
        }
        if (candidates.size() > MAX_LIMIT) {
            throw ApiException.badRequest("Au plus " + MAX_LIMIT + " candidats par import");
        }
        int skipped = 0;
        List<BusinessDTO> leads = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (c == null || blankToNull(c.name()) == null || blankToNull(c.osmId()) == null || !seen.add(c.osmId())
                    || businessService.findExistingLeadId(c.name(), c.phone(), c.addressCity()).isPresent()) {
                skipped++;
                continue;
            }
            Business business = toBusiness(c);
            if (assignToMe) {
                business.setAssignedTo(caller);
            }
            try {
                BusinessService.UpsertResult result = businessService.importRecord(business);
                if (result.created()) {
                    leads.add(priorityScoreService.decorate(DtoMapper.toDto(result.business())));
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                log.warn("OSM import of {} failed: {}", c.osmId(), e.toString());
                skipped++;
            }
        }
        return new ImportResponse(leads.size(), skipped, leads);
    }

    static Business toBusiness(Candidate c) {
        Business b = new Business();
        b.setBusinessName(truncate(c.name().strip(), 255));
        b.setBusinessType(OsmCategories.businessType(c.category()));
        b.setPhone(truncate(blankToNull(c.phone()), 20));
        String email = blankToNull(c.email());
        b.setEmail(email != null && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? truncate(email, 255) : null);
        b.setWebsite(truncate(blankToNull(c.website()), 500));
        b.setAddressStreet(truncate(blankToNull(c.addressStreet()), 255));
        b.setAddressCity(truncate(blankToNull(c.addressCity()), 100));
        String postal = blankToNull(c.postalCode());
        b.setPostalCode(postal != null && postal.replaceAll("\\s", "").length() <= 7 ? postal : null);
        if (c.lat() != null && c.lon() != null && c.lat().abs().doubleValue() <= 90 && c.lon().abs().doubleValue() <= 180) {
            b.setLatitude(c.lat().setScale(8, RoundingMode.HALF_UP));
            b.setLongitude(c.lon().setScale(8, RoundingMode.HALF_UP));
        }
        b.setDataSource(DATA_SOURCE);
        b.setExternalId(truncate(c.osmId(), 255));
        b.setSourceUrl("https://www.openstreetmap.org/" + c.osmId());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Catégorie OSM", blankToNull(c.osmTag()) != null ? c.osmTag() : String.valueOf(c.category()));
        details.put("Source", "OpenStreetMap");
        b.setSourceDetails(details);
        return b;
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal coordinate(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }

    /** OSM multi-values are separated by ';' ("514 555-0100; 514 555-0101"): keep the first. */
    private static String firstValue(String s) {
        if (s == null) return null;
        int semi = s.indexOf(';');
        return blankToNull(semi < 0 ? s : s.substring(0, semi));
    }

    private static String firstNonBlank(String a, String b) {
        return blankToNull(a) != null ? a.strip() : blankToNull(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
