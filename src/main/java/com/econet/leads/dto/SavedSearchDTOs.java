package com.econet.leads.dto;

import com.econet.leads.model.SavedSearch;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Bodies of the /api/saved-searches endpoints. */
public final class SavedSearchDTOs {

    private SavedSearchDTOs() {
    }

    /** categories use the discovery keys (restaurant, clinic...); maxResults 1-300 (default 100); frequency monthly|weekly (default monthly). */
    public record Request(String name, String city, List<String> categories, Integer maxResults, String frequency,
                          UUID assignToUserId, Boolean active) {
    }

    public record Response(UUID id, String name, String city, List<String> categories, int maxResults, String frequency,
                           UUID assignToUserId, String assignToName, boolean active, LocalDateTime lastRunAt,
                           Integer lastAdded, LocalDateTime nextRunAt, UUID createdById, String createdByName,
                           LocalDateTime createdAt) {

        public static Response of(SavedSearch s) {
            return new Response(s.getId(), s.getName(), s.getCity(), List.copyOf(s.getCategories()), s.getMaxResults(),
                    s.getFrequency(), s.getAssignTo() == null ? null : s.getAssignTo().getId(),
                    s.getAssignTo() == null ? null : s.getAssignTo().getUsername(), s.isActive(), s.getLastRunAt(),
                    s.getLastAdded(), null, s.getCreatedBy() == null ? null : s.getCreatedBy().getId(),
                    s.getCreatedBy() == null ? null : s.getCreatedBy().getUsername(), s.getCreatedAt());
        }

        public Response withNextRunAt(LocalDateTime next) {
            return new Response(id, name, city, categories, maxResults, frequency, assignToUserId, assignToName, active,
                    lastRunAt, lastAdded, next, createdById, createdByName, createdAt);
        }
    }
}
