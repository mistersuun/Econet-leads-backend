package com.econet.leads.repository;

import com.econet.leads.model.Client;
import com.econet.leads.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClientRepository extends JpaRepository<Client, UUID> {

    @Query("SELECT c FROM Client c JOIN FETCH c.business b LEFT JOIN FETCH b.assignedTo "
            + "WHERE (:status IS NULL OR c.status = :status) "
            + "AND (:businessId IS NULL OR b.id = :businessId) "
            + "ORDER BY c.createdAt DESC")
    List<Client> search(@Param("status") String status, @Param("businessId") UUID businessId);

    @Query("SELECT c FROM Client c JOIN FETCH c.business b LEFT JOIN FETCH b.assignedTo WHERE c.id = :id")
    Optional<Client> findWithBusiness(@Param("id") UUID id);

    boolean existsByBusinessId(UUID businessId);

    Optional<Client> findByBusinessId(UUID businessId);

    /** Active clients whose renewal date falls in [from, to] (pass an early {@code from} to include overdue ones), soonest first. */
    @Query("SELECT c FROM Client c JOIN FETCH c.business b LEFT JOIN b.assignedTo u "
            + "WHERE c.status = 'active' AND c.contractEndDate BETWEEN :from AND :to "
            + "AND (u IS NULL OR u.id = :userId) ORDER BY c.contractEndDate ASC")
    List<Client> findRenewals(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("userId") UUID userId);

    /** Active clients whose first cleaning is done since {@code cutoff} or earlier and who were never asked for a review. */
    @Query("SELECT c FROM Client c JOIN FETCH c.business b LEFT JOIN b.assignedTo u "
            + "WHERE c.status = 'active' AND c.firstCleaningDoneAt IS NOT NULL AND c.firstCleaningDoneAt <= :cutoff "
            + "AND c.reviewRequestedAt IS NULL AND (u IS NULL OR u.id = :userId) ORDER BY c.firstCleaningDoneAt ASC")
    List<Client> findReviewDue(@Param("cutoff") LocalDateTime cutoff, @Param("userId") UUID userId);

    @Query("SELECT c FROM Client c JOIN FETCH c.business b LEFT JOIN b.assignedTo u "
            + "WHERE c.status = 'active' AND c.nextVisitAt >= :from AND c.nextVisitAt < :to "
            + "AND (u IS NULL OR u.id = :userId) ORDER BY c.nextVisitAt ASC")
    List<Client> findVisitsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("userId") UUID userId);

    long countByReviewRequestedAtIsNullAndFirstCleaningDoneAtIsNotNullAndStatus(String status);
}
