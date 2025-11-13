package com.econet.leads.repository;

import com.econet.leads.model.Business;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessRepository extends JpaRepository<Business, UUID>, JpaSpecificationExecutor<Business> {

    // Find by exact name and city (for duplicate detection)
    Optional<Business> findByBusinessNameAndAddressCity(String businessName, String addressCity);

    // Find by phone (for duplicate detection)
    Optional<Business> findByPhone(String phone);

    // Find by normalized phone (for duplicate detection)
    Optional<Business> findByPhoneNormalized(String phoneNormalized);

    // Find by external ID and source (for update detection)
    Optional<Business> findByExternalIdAndDataSource(String externalId, String dataSource);

    // Find all by business type
    Page<Business> findByBusinessType(String businessType, Pageable pageable);

    // Find all by city
    Page<Business> findByAddressCity(String city, Pageable pageable);

    // Find all by data source
    Page<Business> findByDataSource(String dataSource, Pageable pageable);

    // Find similar businesses (same type and city) for fuzzy duplicate detection
    List<Business> findByAddressCityAndBusinessType(String city, String businessType);

    // Full-text search on business name
    @Query(value = "SELECT * FROM businesses WHERE to_tsvector('french', business_name) @@ plainto_tsquery('french', :searchTerm)",
           countQuery = "SELECT COUNT(*) FROM businesses WHERE to_tsvector('french', business_name) @@ plainto_tsquery('french', :searchTerm)",
           nativeQuery = true)
    Page<Business> fullTextSearch(@Param("searchTerm") String searchTerm, Pageable pageable);

    // Count by business type
    Long countByBusinessType(String businessType);

    // Count by data source
    Long countByDataSource(String dataSource);

    // Find stale businesses (not verified recently)
    @Query("SELECT b FROM Business b WHERE b.lastVerified IS NULL OR b.lastVerified < :cutoffDate")
    List<Business> findStaleBusinesses(@Param("cutoffDate") java.time.LocalDateTime cutoffDate);
}
