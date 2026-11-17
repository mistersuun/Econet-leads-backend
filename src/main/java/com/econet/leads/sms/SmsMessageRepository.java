package com.econet.leads.sms;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface SmsMessageRepository extends JpaRepository<SmsMessage, UUID> {

    boolean existsByBusinessIdAndKind(UUID businessId, String kind);

    long countByKindAndCreatedAtGreaterThanEqual(String kind, LocalDateTime since);

    /** Real alerts only: test alerts are stored without a lead. */
    long countByKindAndBusinessIdIsNotNullAndCreatedAtGreaterThanEqual(String kind, LocalDateTime since);
}
