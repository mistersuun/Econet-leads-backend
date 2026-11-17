package com.econet.leads.repository;

import com.econet.leads.model.BookingLink;
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
public interface BookingLinkRepository extends JpaRepository<BookingLink, UUID> {

    @Query("SELECT l FROM BookingLink l JOIN FETCH l.business WHERE l.token = :token")
    Optional<BookingLink> findByToken(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM BookingLink l JOIN FETCH l.business WHERE l.token = :token")
    Optional<BookingLink> findByTokenForUpdate(@Param("token") String token);

    /** Latest unused, unexpired link of the lead, for the {{bookingLink}} placeholder. */
    @Query("SELECT l FROM BookingLink l WHERE l.business.id = :businessId AND l.usedAt IS NULL AND l.expiresAt > :now ORDER BY l.createdAt DESC LIMIT 1")
    Optional<BookingLink> findOpenForLead(@Param("businessId") UUID businessId, @Param("now") LocalDateTime now);

    /** Links created by the user and used since {@code since} (notification source). */
    @Query("SELECT l FROM BookingLink l JOIN FETCH l.business WHERE l.createdBy.id = :userId AND l.usedAt >= :since ORDER BY l.usedAt DESC")
    List<BookingLink> findUsedSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);
}
