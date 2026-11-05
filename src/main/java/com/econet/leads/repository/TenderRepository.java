package com.econet.leads.repository;

import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.econet.leads.model.TenderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TenderRepository extends JpaRepository<Tender, UUID>, JpaSpecificationExecutor<Tender> {

    Optional<Tender> findBySourceAndExternalId(TenderSource source, String externalId);

    @Query("SELECT COUNT(t) FROM Tender t WHERE t.closingAt >= :from AND t.closingAt < :to AND t.status NOT IN :excluded"
            + " AND (t.noticeType IS NULL OR t.noticeType <> 'AWARD')")
    long countClosingBetween(@Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             @Param("excluded") Collection<TenderStatus> excluded);

    /** Tenders in the given statuses closing in [from, to] (notifications), soonest first. */
    @Query("SELECT t FROM Tender t WHERE t.closingAt >= :from AND t.closingAt <= :to AND t.status IN :statuses"
            + " ORDER BY t.closingAt ASC, t.id ASC")
    java.util.List<com.econet.leads.model.Tender> findClosingBetween(@Param("from") LocalDateTime from,
                                                                       @Param("to") LocalDateTime to,
                                                                       @Param("statuses") Collection<TenderStatus> statuses);

    @Query("SELECT COUNT(t) FROM Tender t WHERE t.closingAt >= :now AND t.status NOT IN :excluded"
            + " AND (t.noticeType IS NULL OR t.noticeType <> 'AWARD')")
    long countOpen(@Param("now") LocalDateTime now, @Param("excluded") Collection<TenderStatus> excluded);

    long countByStatus(TenderStatus status);
}
