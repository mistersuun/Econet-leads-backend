package com.econet.leads.repository;

import com.econet.leads.model.PlacesUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface PlacesUsageRepository extends JpaRepository<PlacesUsage, LocalDate> {
}
