package com.econet.leads.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the OpenStreetMap discovery endpoints. */
public final class DiscoverDTOs {

    private DiscoverDTOs() {
    }

    public static final String ATTRIBUTION = "© OpenStreetMap contributors";

    public record SearchRequest(String city, List<String> categories, Integer limit) {
    }

    /** osmTag ("amenity=restaurant") is an addition to the contract: it feeds sourceDetails["Catégorie OSM"] on import. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(String osmId, String name, String category, String phone, String website, String email,
                            String addressStreet, String addressCity, String postalCode, BigDecimal lat, BigDecimal lon,
                            UUID existingLeadId, String osmTag) {
    }

    public record SearchResponse(List<Candidate> candidates, String attribution) {
    }

    public record ImportRequest(List<Candidate> candidates, Boolean assignToMe) {
    }

    public record ImportResponse(int added, int skipped, List<BusinessDTO> leads) {
    }
}
