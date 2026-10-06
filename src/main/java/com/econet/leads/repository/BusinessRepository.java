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

    // --- Calling queue (see LeadService#getQueue) ---

    @Query("""
            SELECT b FROM Business b LEFT JOIN b.assignedTo u
            WHERE b.nextFollowUpAt IS NOT NULL AND b.nextFollowUpAt <= :now
              AND b.leadStatus NOT IN :excluded
              AND b.phone IS NOT NULL AND b.phone <> ''
              AND (u IS NULL OR u.id = :userId)
            ORDER BY b.nextFollowUpAt ASC, b.id ASC
            """)
    List<Business> findQueueFollowUpsDue(@Param("now") java.time.LocalDateTime now,
                                         @Param("excluded") java.util.Collection<com.econet.leads.model.LeadStatus> excluded,
                                         @Param("userId") UUID userId,
                                         Pageable pageable);

    @Query("""
            SELECT b FROM Business b LEFT JOIN b.assignedTo u
            WHERE b.leadStatus = com.econet.leads.model.LeadStatus.NEW
              AND b.phone IS NOT NULL AND b.phone <> ''
              AND (b.nextFollowUpAt IS NULL OR b.nextFollowUpAt > :now)
              AND (u IS NULL OR u.id = :userId)
            ORDER BY b.dataQualityScore DESC NULLS LAST, b.createdAt ASC, b.id ASC
            """)
    List<Business> findQueueNewLeads(@Param("now") java.time.LocalDateTime now,
                                     @Param("userId") UUID userId,
                                     Pageable pageable);

    // --- Filter options (GET /api/businesses/filters) ---

    @Query("SELECT DISTINCT b.businessType FROM Business b WHERE b.businessType IS NOT NULL AND b.businessType <> ''")
    List<String> findDistinctBusinessTypes();

    @Query("SELECT DISTINCT b.dataSource FROM Business b WHERE b.dataSource IS NOT NULL AND b.dataSource <> ''")
    List<String> findDistinctDataSources();

    @Query("""
            SELECT b.addressCity FROM Business b
            WHERE b.addressCity IS NOT NULL AND b.addressCity <> ''
            GROUP BY b.addressCity
            ORDER BY COUNT(b) DESC, b.addressCity ASC
            """)
    List<String> findTopCities(Pageable pageable);

    // Row lock for read-modify-write of the pipeline counters (call logging)
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Business b WHERE b.id = :id")
    Optional<Business> findByIdForUpdate(@Param("id") UUID id);
}
