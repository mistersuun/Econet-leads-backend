package com.econet.leads.repository;

import com.econet.leads.model.CallScript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CallScriptRepository extends JpaRepository<CallScript, UUID> {

    List<CallScript> findByActiveTrueAndLanguageAndBusinessTypeOrderByUpdatedAtDesc(String language, String businessType);

    List<CallScript> findByActiveTrueAndLanguageAndBusinessTypeIsNullOrderByUpdatedAtDesc(String language);

    List<CallScript> findAllByOrderByLanguageAscBusinessTypeAscNameAsc();
}
