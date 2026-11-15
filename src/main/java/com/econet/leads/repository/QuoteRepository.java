package com.econet.leads.repository;

import com.econet.leads.model.Quote;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuoteRepository extends JpaRepository<Quote, UUID> {

    @Query("SELECT q FROM Quote q LEFT JOIN FETCH q.createdBy WHERE q.business.id = :businessId ORDER BY q.createdAt DESC")
    List<Quote> findByBusinessId(@Param("businessId") UUID businessId);

    @Query("SELECT q FROM Quote q JOIN FETCH q.business WHERE q.token = :token")
    Optional<Quote> findByToken(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM Quote q JOIN FETCH q.business WHERE q.token = :token")
    Optional<Quote> findByTokenForUpdate(@Param("token") String token);

    @Query("SELECT q.number FROM Quote q WHERE q.number LIKE :prefix ORDER BY q.number DESC LIMIT 1")
    Optional<String> findLastNumber(@Param("prefix") String prefix);

    /** Accepted since {@code since}, created by the user (notification source). */
    @Query("SELECT q FROM Quote q JOIN FETCH q.business WHERE q.createdBy.id = :userId AND q.status = 'accepted' AND q.acceptedAt >= :since ORDER BY q.acceptedAt DESC")
    List<Quote> findAcceptedSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /** Latest quote of the lead that can still be accepted (draft or sent), for the {{quoteLink}} placeholder. */
    @Query("SELECT q FROM Quote q WHERE q.business.id = :businessId AND q.status IN ('draft', 'sent') AND q.validUntil >= :today ORDER BY q.createdAt DESC LIMIT 1")
    Optional<Quote> findOpenForLead(@Param("businessId") UUID businessId, @Param("today") java.time.LocalDate today);

    /** Latest accepted quote of a lead: the source of the client's service, frequency and price (P6). */
    Optional<Quote> findFirstByBusinessIdAndStatusOrderByAcceptedAtDesc(UUID businessId, String status);
}
