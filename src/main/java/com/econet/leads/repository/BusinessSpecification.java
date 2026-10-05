package com.econet.leads.repository;

import com.econet.leads.dto.BusinessFilterDTO;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class BusinessSpecification {

    private BusinessSpecification() {
    }

    /**
     * Builds the list/export filter. {@code now} is used for the followUpDue filter.
     *
     * @throws IllegalArgumentException for unknown leadStatus values
     */
    public static Specification<Business> withFilters(BusinessFilterDTO filters, LocalDateTime now) {
        Set<LeadStatus> statuses = parseStatuses(filters.getLeadStatus());

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (hasText(filters.getQ())) {
                String q = filters.getQ().trim().toLowerCase(Locale.ROOT);
                String pattern = "%" + escapeLike(q) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("businessName")), pattern, '!'));
                any.add(cb.like(cb.lower(root.get("addressCity")), pattern, '!'));
                any.add(cb.like(cb.lower(root.get("phone")), pattern, '!'));
                String digits = q.replaceAll("[^0-9]", "");
                if (digits.length() >= 3) {
                    any.add(cb.like(root.get("phoneNormalized"), "%" + digits + "%"));
                }
                predicates.add(cb.or(any.toArray(new Predicate[0])));
            }

            if (!statuses.isEmpty()) {
                predicates.add(root.get("leadStatus").in(statuses));
            }

            if (hasText(filters.getBusinessType())) {
                predicates.add(cb.equal(root.get("businessType"), filters.getBusinessType()));
            }

            if (hasText(filters.getCity())) {
                predicates.add(cb.equal(cb.lower(root.get("addressCity")), filters.getCity().trim().toLowerCase(Locale.ROOT)));
            }

            if (hasText(filters.getProvince())) {
                predicates.add(cb.equal(root.get("addressProvince"), filters.getProvince()));
            }

            if (hasText(filters.getDataSource())) {
                predicates.add(cb.equal(root.get("dataSource"), filters.getDataSource()));
            }

            if (filters.getHasPhone() != null) {
                Predicate hasPhone = cb.and(cb.isNotNull(root.get("phone")), cb.notEqual(root.get("phone"), ""));
                predicates.add(filters.getHasPhone() ? hasPhone : cb.not(hasPhone));
            }

            if (filters.getAssignedToUserId() != null) {
                predicates.add(cb.equal(root.get("assignedTo").get("id"), filters.getAssignedToUserId()));
            }

            if (Boolean.TRUE.equals(filters.getFollowUpDue())) {
                predicates.add(cb.lessThanOrEqualTo(root.get("nextFollowUpAt"), now));
            }

            if (hasText(filters.getSearchTerm())) {
                String searchPattern = "%" + escapeLike(filters.getSearchTerm().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("businessName")), searchPattern, '!'),
                        cb.like(cb.lower(root.get("addressStreet")), searchPattern, '!')));
            }

            if (filters.getMinQualityScore() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dataQualityScore"), filters.getMinQualityScore()));
            }

            if (filters.getMaxQualityScore() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dataQualityScore"), filters.getMaxQualityScore()));
            }

            if (Boolean.TRUE.equals(filters.getStale())) {
                // Consider stale if last verified > 90 days ago or never verified
                LocalDateTime cutoffDate = now.minusDays(90);
                predicates.add(cb.or(
                        cb.isNull(root.get("lastVerified")),
                        cb.lessThan(root.get("lastVerified"), cutoffDate)));
            }

            if (hasText(filters.getContactStatus())) {
                // Businesses whose most recent contact has the given status
                Subquery<UUID> contactSubquery = query.subquery(UUID.class);
                Root<Contact> contactRoot = contactSubquery.from(Contact.class);

                Subquery<LocalDateTime> maxDateSubquery = query.subquery(LocalDateTime.class);
                Root<Contact> maxDateRoot = maxDateSubquery.from(Contact.class);
                maxDateSubquery.select(cb.greatest(maxDateRoot.<LocalDateTime>get("contactDate")))
                        .where(cb.equal(maxDateRoot.get("business").get("id"), contactRoot.get("business").get("id")));

                contactSubquery.select(contactRoot.get("business").get("id"))
                        .where(cb.and(
                                cb.equal(contactRoot.get("contactStatus"), filters.getContactStatus()),
                                cb.equal(contactRoot.get("contactDate"), maxDateSubquery)));

                predicates.add(root.get("id").in(contactSubquery));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    static Set<LeadStatus> parseStatuses(List<String> raw) {
        Set<LeadStatus> statuses = EnumSet.noneOf(LeadStatus.class);
        if (raw == null) {
            return statuses;
        }
        for (String entry : raw) {
            if (entry == null) continue;
            for (String part : entry.split(",")) {
                String value = part.trim();
                if (value.isEmpty()) continue;
                try {
                    statuses.add(LeadStatus.valueOf(value.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid leadStatus '" + value + "'. Allowed: "
                            + Arrays.toString(LeadStatus.values()));
                }
            }
        }
        return statuses;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
