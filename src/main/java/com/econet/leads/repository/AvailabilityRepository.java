package com.econet.leads.repository;

import com.econet.leads.model.Availability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AvailabilityRepository extends JpaRepository<Availability, UUID> {

    List<Availability> findAllByOrderByWeekdayAscStartTimeAsc();

    List<Availability> findByActiveTrueOrderByWeekdayAscStartTimeAsc();
}
