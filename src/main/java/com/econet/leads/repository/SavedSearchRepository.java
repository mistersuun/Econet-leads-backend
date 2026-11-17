package com.econet.leads.repository;

import com.econet.leads.model.SavedSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface SavedSearchRepository extends JpaRepository<SavedSearch, UUID> {

    List<SavedSearch> findAllByOrderByCreatedAtDesc();

    List<SavedSearch> findByActiveTrue();

    /** Searches that ran recently and added leads (notifications). */
    List<SavedSearch> findByLastRunAtAfterAndLastAddedGreaterThan(LocalDateTime since, int minAdded);
}
