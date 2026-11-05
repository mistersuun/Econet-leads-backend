package com.econet.leads.repository;

import com.econet.leads.model.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {

    Optional<EmailTemplate> findByKeyAndLanguageAndActiveTrue(String key, String language);

    List<EmailTemplate> findAllByOrderByKeyAscLanguageAsc();
}
