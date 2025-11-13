package com.econet.leads.repository;

import com.econet.leads.dto.BusinessFilterDTO;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BusinessSpecification {

    public static Specification<Business> withFilters(BusinessFilterDTO filters) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filters.getBusinessType() != null && !filters.getBusinessType().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("businessType"), filters.getBusinessType()));
            }

            if (filters.getCity() != null && !filters.getCity().isEmpty()) {
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("addressCity")),
                        "%" + filters.getCity().toLowerCase() + "%"
                ));
            }

            if (filters.getProvince() != null && !filters.getProvince().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("addressProvince"), filters.getProvince()));
            }

            if (filters.getDataSource() != null && !filters.getDataSource().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("dataSource"), filters.getDataSource()));
            }

            if (filters.getSearchTerm() != null && !filters.getSearchTerm().isEmpty()) {
                String searchPattern = "%" + filters.getSearchTerm().toLowerCase() + "%";
                Predicate namePredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("businessName")),
                        searchPattern
                );
                Predicate addressPredicate = criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("addressStreet")),
                        searchPattern
                );
                predicates.add(criteriaBuilder.or(namePredicate, addressPredicate));
            }

            if (filters.getMinQualityScore() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.get("dataQualityScore"),
                        filters.getMinQualityScore()
                ));
            }

            if (filters.getMaxQualityScore() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                        root.get("dataQualityScore"),
                        filters.getMaxQualityScore()
                ));
            }

            if (filters.getStale() != null && filters.getStale()) {
                // Consider stale if last verified > 90 days ago or never verified
                LocalDateTime cutoffDate = LocalDateTime.now().minusDays(90);
                Predicate neverVerified = criteriaBuilder.isNull(root.get("lastVerified"));
                Predicate oldVerification = criteriaBuilder.lessThan(root.get("lastVerified"), cutoffDate);
                predicates.add(criteriaBuilder.or(neverVerified, oldVerification));
            }

            if (filters.getContactStatus() != null && !filters.getContactStatus().isEmpty()) {
                // Subquery to find businesses with a contact matching the status
                Subquery<UUID> contactSubquery = query.subquery(UUID.class);
                Root<Contact> contactRoot = contactSubquery.from(Contact.class);

                // Subquery to find the most recent contact for each business
                Subquery<LocalDateTime> maxDateSubquery = query.subquery(LocalDateTime.class);
                Root<Contact> maxDateRoot = maxDateSubquery.from(Contact.class);
                maxDateSubquery.select(criteriaBuilder.greatest(maxDateRoot.<LocalDateTime>get("contactDate")))
                    .where(criteriaBuilder.equal(maxDateRoot.get("businessId"), contactRoot.get("businessId")));

                // Select businesses where the contact status matches and is the most recent
                contactSubquery.select(contactRoot.get("businessId"))
                    .where(
                        criteriaBuilder.and(
                            criteriaBuilder.equal(contactRoot.get("contactStatus"), filters.getContactStatus()),
                            criteriaBuilder.equal(contactRoot.get("contactDate"), maxDateSubquery)
                        )
                    );

                predicates.add(root.get("id").in(contactSubquery));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
