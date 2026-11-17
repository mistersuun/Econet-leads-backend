package com.econet.leads.repository;

import com.econet.leads.model.Visit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    @Query("SELECT v FROM Visit v JOIN FETCH v.business WHERE v.startsAt >= :from AND v.startsAt < :to ORDER BY v.startsAt")
    List<Visit> findBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Booked visits overlapping [from, to). */
    @Query("SELECT v FROM Visit v WHERE v.status = 'booked' AND v.startsAt < :to AND v.endsAt > :from")
    List<Visit> findBookedOverlapping(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
