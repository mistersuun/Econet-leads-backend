package com.econet.leads.repository;

import com.econet.leads.model.Contact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {

    // Find all contacts for a business with eager loading
    @Query("SELECT c FROM Contact c LEFT JOIN FETCH c.business LEFT JOIN FETCH c.user WHERE c.business.id = :businessId ORDER BY c.contactDate DESC")
    List<Contact> findByBusinessIdOrderByContactDateDesc(@Param("businessId") UUID businessId);

    // Find all contacts by a user with eager loading
    @Query(value = "SELECT c FROM Contact c LEFT JOIN FETCH c.business LEFT JOIN FETCH c.user WHERE c.user.id = :userId ORDER BY c.contactDate DESC",
           countQuery = "SELECT COUNT(c) FROM Contact c WHERE c.user.id = :userId")
    Page<Contact> findByUserIdOrderByContactDateDesc(@Param("userId") UUID userId, Pageable pageable);

    // Find all contacts with eager loading (for paginated lists)
    @Query(value = "SELECT c FROM Contact c LEFT JOIN FETCH c.business LEFT JOIN FETCH c.user",
           countQuery = "SELECT COUNT(c) FROM Contact c")
    Page<Contact> findAllWithAssociations(Pageable pageable);

    // Find upcoming follow-ups with eager loading
    @Query("SELECT c FROM Contact c LEFT JOIN FETCH c.business LEFT JOIN FETCH c.user WHERE c.nextActionDate IS NOT NULL AND c.nextActionDate >= :startDate AND c.nextActionDate <= :endDate ORDER BY c.nextActionDate")
    List<Contact> findUpcomingFollowUps(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    // Find contacts by status
    Page<Contact> findByContactStatus(String status, Pageable pageable);

    // Find contacts by outcome
    Page<Contact> findByOutcome(Contact.ContactOutcome outcome, Pageable pageable);

    // Count contacts by user
    Long countByUserId(UUID userId);

    // Count contacts by status
    Long countByContactStatus(String status);

    // Find recent contacts with eager loading
    @Query("SELECT c FROM Contact c LEFT JOIN FETCH c.business LEFT JOIN FETCH c.user WHERE c.contactDate >= :since ORDER BY c.contactDate DESC")
    List<Contact> findRecentContacts(@Param("since") LocalDateTime since);

    // Find contact by ID with associations
    @Query("SELECT c FROM Contact c LEFT JOIN FETCH c.business LEFT JOIN FETCH c.user WHERE c.id = :id")
    Optional<Contact> findByIdWithAssociations(@Param("id") UUID id);
}
