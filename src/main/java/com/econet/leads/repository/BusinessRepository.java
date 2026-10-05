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

    /*
     * Duplicate / update lookups used by imports.
     *
     * These used to return Optional<Business> from derived queries (findByPhoneNormalized, ...).
     * When two or more rows matched (very common: chains and franchises share a head-office phone
     * number, and some sources contain the same name+city twice) Spring Data threw
     * IncorrectResultSizeDataAccessException. Because the repository proxy is transactional, that
     * exception also marked the surrounding import transaction rollback-only, which surfaced as
     * "Transaction silently rolled back because it has been marked as rollback-only" and failed the
     * whole import. The variants below are bounded, deterministic (oldest record first) and never
     * throw when several rows match; callers filter the small result list in memory.
     */
    List<Business> findTop10ByBusinessNameAndAddressCityOrderByCreatedAtAsc(String businessName, String addressCity);

    List<Business> findTop20ByPhoneNormalizedOrderByCreatedAtAsc(String phoneNormalized);

    Optional<Business> findFirstByExternalIdAndDataSourceOrderByCreatedAtAsc(String externalId, String dataSource);

    /**
     * Bounded candidate set for fuzzy duplicate detection (see BusinessService#findFuzzyDuplicate).
     * Only rows in the same city and type whose lower-cased name starts with the same prefix and
     * whose name length is within the range that could possibly reach the similarity threshold.
     * The caller passes a Pageable to cap the number of rows compared.
     */
    @Query("""
            SELECT b FROM Business b
            WHERE b.addressCity = :city
              AND b.businessType = :businessType
              AND LOWER(b.businessName) LIKE :namePrefix ESCAPE '!'
              AND LENGTH(b.businessName) BETWEEN :minLength AND :maxLength
            ORDER BY b.createdAt ASC
            """)
    List<Business> findFuzzyCandidates(@Param("city") String city,
                                       @Param("businessType") String businessType,
                                       @Param("namePrefix") String namePrefix,
                                       @Param("minLength") int minLength,
                                       @Param("maxLength") int maxLength,
                                       Pageable pageable);

    // Find all by business type
    Page<Business> findByBusinessType(String businessType, Pageable pageable);

    // Find all by city
    Page<Business> findByAddressCity(String city, Pageable pageable);

    // Find all by data source
    Page<Business> findByDataSource(String dataSource, Pageable pageable);

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
