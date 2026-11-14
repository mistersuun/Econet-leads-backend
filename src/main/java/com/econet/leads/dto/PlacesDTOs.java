package com.econet.leads.dto;

import java.util.List;
import java.util.UUID;

/** Request/response bodies of the Google Places enrichment endpoints. */
public final class PlacesDTOs {

    private PlacesDTOs() {
    }

    public static final String ATTRIBUTION = "Données Google";

    public record EnrichRequest(List<UUID> ids) {
    }

    /** configured is false while GOOGLE_PLACES_API_KEY is empty. */
    public record Status(boolean configured, int usedToday, int dailyLimit, String attribution) {
    }
}
