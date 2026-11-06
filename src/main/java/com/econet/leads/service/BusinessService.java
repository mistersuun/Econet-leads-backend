package com.econet.leads.service;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.BusinessFilterOptionsDTO;
import com.econet.leads.exception.ApiException;
import com.econet.leads.exception.DuplicateLeadException;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Business;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.util.AddressNormalizer;
import com.econet.leads.util.PhoneFormatter;
import com.econet.leads.util.PostalCodeValidator;
import com.econet.leads.util.StringSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final DataQualityService dataQualityService;
    private final PriorityScoreService priorityScoreService;

    @Transactional(readOnly = true)
    public Page<Business> findAll(Pageable pageable) {
        return businessRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Business> findWithFilters(Specification<Business> spec, Pageable pageable) {
        return businessRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Business> findById(UUID id) {
        return businessRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<BusinessDTO> findDtos(Specification<Business> spec, Pageable pageable) {
        java.util.Map<String, Double> rates = priorityScoreService.ratesByType();
        return businessRepository.findAll(spec, pageable).map(b -> priorityScoreService.decorate(DtoMapper.toDto(b), rates));
    }

    @Transactional(readOnly = true)
    public Optional<BusinessDTO> findDtoById(UUID id) {
        return businessRepository.findById(id).map(b -> priorityScoreService.decorate(DtoMapper.toDto(b)));
    }

    /**
     * Distinct values for the list filters, sorted with French collation; cities are the 200 most
     * frequent ones.
     */
    @Transactional(readOnly = true)
    public BusinessFilterOptionsDTO getFilterOptions() {
        Collator collator = Collator.getInstance(Locale.CANADA_FRENCH);
        collator.setStrength(Collator.SECONDARY);
        List<String> types = new ArrayList<>(businessRepository.findDistinctBusinessTypes());
        List<String> sources = new ArrayList<>(businessRepository.findDistinctDataSources());
        List<String> cities = new ArrayList<>(businessRepository.findTopCities(PageRequest.of(0, 200)));
        types.sort(collator);
        sources.sort(collator);
        cities.sort(collator);
        return new BusinessFilterOptionsDTO(types, cities, sources);
    }

    @Transactional(readOnly = true)
    public Page<Business> search(String searchTerm, Pageable pageable) {
        return businessRepository.fullTextSearch(searchTerm, pageable);
    }

    @Transactional
    public Business create(Business business) {
        // Normalize data
        normalizeBusinessData(business);

        // Check for duplicates
        Optional<Business> duplicate = findDuplicate(business);
        if (duplicate.isPresent()) {
            log.warn("Duplicate business found: {}", duplicate.get().getId());
            throw ApiException.conflict("Duplicate business already exists: " + duplicate.get().getBusinessName());
        }

        // Calculate quality score
        int qualityScore = dataQualityService.calculateQualityScore(business);
        business.setDataQualityScore(qualityScore);

        return businessRepository.save(business);
    }

    /**
     * Creates a lead typed in by a person. Only an EXACT duplicate (same name in the same city, or the
     * same phone in the same city) is refused with {@link DuplicateLeadException}; fuzzy look-alikes are
     * only hints (see {@link #findDuplicates}). {@code force} skips the check.
     */
    @Transactional
    public Business createManual(Business business, boolean force) {
        normalizeBusinessData(business);
        if (!force) {
            Optional<Business> exact = findExactDuplicate(business);
            if (exact.isPresent()) {
                throw new DuplicateLeadException("Ce lead existe déjà : " + exact.get().getBusinessName()
                        + (exact.get().getAddressCity() != null ? " (" + exact.get().getAddressCity() + ")" : ""),
                        priorityScoreService.decorate(DtoMapper.toDto(exact.get())));
            }
        }
        business.setDataQualityScore(dataQualityService.calculateQualityScore(business));
        return businessRepository.save(business);
    }

    /**
     * Id of the existing lead an external candidate (OpenStreetMap...) would duplicate: same name in the
     * same city, or same phone in the same city. Only exact matches: this flags "existe déjà" in lists.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findExistingLeadId(String name, String phone, String city) {
        Business probe = new Business();
        probe.setBusinessName(name);
        probe.setPhone(phone);
        probe.setAddressCity(city);
        normalizeBusinessData(probe);
        return findExactDuplicate(probe).map(Business::getId);
    }

    /** Same name in the same city, or same normalized phone in the same city (both cities null counts as same). */
    Optional<Business> findExactDuplicate(Business candidate) {
        String city = candidate.getAddressCity();
        if (candidate.getBusinessName() != null && city != null) {
            Optional<Business> byName = businessRepository
                    .findTop10ByBusinessNameAndAddressCityOrderByCreatedAtAsc(candidate.getBusinessName(), city)
                    .stream().findFirst();
            if (byName.isPresent()) {
                return byName;
            }
        }
        String phone = PhoneFormatter.normalize(candidate.getPhone());
        if (phone != null && !phone.isEmpty()) {
            return businessRepository.findTop20ByPhoneNormalizedOrderByCreatedAtAsc(phone).stream()
                    .filter(b -> b.getAddressCity() == null ? city == null : b.getAddressCity().equalsIgnoreCase(city))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * "Existe déjà ?" hints while typing: up to {@code limit} leads matching by the same rules as
     * import deduplication, strongest first: exact name (+ city when given), same phone (compatible
     * city), fuzzy name (similarity above {@value #FUZZY_THRESHOLD}, same city when given).
     */
    @Transactional(readOnly = true)
    public List<BusinessDTO> findDuplicates(String name, String phone, String city, int limit) {
        java.util.Map<UUID, Business> found = new java.util.LinkedHashMap<>();
        String cleanName = name == null ? null : name.trim();
        String cleanCity = city == null || city.isBlank() ? null : AddressNormalizer.normalizeCity(city.trim());
        String normalizedPhone = PhoneFormatter.normalize(phone == null || phone.isBlank() ? null : phone);

        if (cleanName != null && !cleanName.isEmpty()) {
            List<Business> exact = cleanCity != null
                    ? businessRepository.findTop10ByBusinessNameAndAddressCityOrderByCreatedAtAsc(cleanName, cleanCity)
                    : businessRepository.findTop10ByBusinessNameIgnoreCaseOrderByCreatedAtAsc(cleanName);
            exact.forEach(b -> found.putIfAbsent(b.getId(), b));
        }
        if (normalizedPhone != null && normalizedPhone.length() >= 10) {
            businessRepository.findTop20ByPhoneNormalizedOrderByCreatedAtAsc(normalizedPhone).stream()
                    .filter(b -> citiesCompatible(cleanCity, b.getAddressCity()))
                    .forEach(b -> found.putIfAbsent(b.getId(), b));
        }
        if (cleanName != null && cleanName.length() >= FUZZY_PREFIX_LENGTH && found.size() < limit) {
            String lower = cleanName.toLowerCase(Locale.ROOT);
            int len = lower.length();
            int minLength = (int) Math.floor(len * FUZZY_THRESHOLD);
            int maxLength = (int) Math.ceil(len / FUZZY_THRESHOLD);
            String likePattern = escapeLike(lower.substring(0, FUZZY_PREFIX_LENGTH)) + "%";
            PageRequest page = PageRequest.of(0, FUZZY_MAX_CANDIDATES);
            List<Business> candidates = cleanCity != null
                    ? businessRepository.findFuzzyCandidatesAnyType(cleanCity, likePattern, minLength, maxLength, page)
                    : businessRepository.findFuzzyCandidatesAnyCity(likePattern, minLength, maxLength, page);
            for (Business b : candidates) {
                if (StringSimilarity.calculate(cleanName, b.getBusinessName()) > FUZZY_THRESHOLD) {
                    found.putIfAbsent(b.getId(), b);
                }
            }
        }
        java.util.Map<String, Double> rates = priorityScoreService.ratesByType();
        return found.values().stream().limit(limit)
                .map(b -> priorityScoreService.decorate(DtoMapper.toDto(b), rates)).toList();
    }

    @Transactional
    public Business update(UUID id, Business updatedBusiness) {
        Business existing = businessRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Business not found with id: " + id));
        String nameBefore = existing.getBusinessName();
        String cityBefore = existing.getAddressCity();
        String phoneBefore = existing.getPhoneNormalized();

        // Patch semantics - only update non-null fields
        if (updatedBusiness.getBusinessName() != null) {
            existing.setBusinessName(updatedBusiness.getBusinessName());
        }
        if (updatedBusiness.getBusinessType() != null) {
            existing.setBusinessType(updatedBusiness.getBusinessType());
        }
        if (updatedBusiness.getCategory() != null) {
            existing.setCategory(updatedBusiness.getCategory());
        }
        if (updatedBusiness.getAddressStreet() != null) {
            existing.setAddressStreet(updatedBusiness.getAddressStreet());
        }
        if (updatedBusiness.getAddressCity() != null) {
            existing.setAddressCity(updatedBusiness.getAddressCity());
        }
        if (updatedBusiness.getAddressProvince() != null) {
            existing.setAddressProvince(updatedBusiness.getAddressProvince());
        }
        if (updatedBusiness.getPostalCode() != null) {
            existing.setPostalCode(updatedBusiness.getPostalCode());
        }
        if (updatedBusiness.getPhone() != null) {
            existing.setPhone(updatedBusiness.getPhone());
        }
        if (updatedBusiness.getEmail() != null) {
            existing.setEmail(updatedBusiness.getEmail());
        }
        if (updatedBusiness.getWebsite() != null) {
            existing.setWebsite(updatedBusiness.getWebsite());
        }
        if (updatedBusiness.getLatitude() != null) {
            existing.setLatitude(updatedBusiness.getLatitude());
        }
        if (updatedBusiness.getLongitude() != null) {
            existing.setLongitude(updatedBusiness.getLongitude());
        }
        if (updatedBusiness.getEstimatedValue() != null) {
            existing.setEstimatedValue(updatedBusiness.getEstimatedValue());
        }
        if (updatedBusiness.getAssignedTo() != null) {
            existing.setAssignedTo(updatedBusiness.getAssignedTo());
        }

        // Immutable fields - cannot be changed after creation
        // dataSource, externalId, sourceUrl are locked down

        // Normalize data
        normalizeBusinessData(existing);

        // Check for duplicates (excluding current business), only when identifying fields changed:
        // CRM edits (estimated value, assignee, ...) must not be rejected because of pre-existing
        // look-alikes such as chain branches sharing a phone number.
        boolean identityChanged = !Objects.equals(nameBefore, existing.getBusinessName())
                || !Objects.equals(cityBefore, existing.getAddressCity())
                || !Objects.equals(phoneBefore, existing.getPhoneNormalized());
        Optional<Business> duplicate = identityChanged ? findDuplicateExcluding(existing, id) : Optional.empty();
        if (duplicate.isPresent()) {
            log.warn("Update would create duplicate: {}", duplicate.get().getId());
            throw ApiException.conflict("Update rejected: would create duplicate of business: " + duplicate.get().getBusinessName());
        }

        // Recalculate quality score
        int qualityScore = dataQualityService.calculateQualityScore(existing);
        existing.setDataQualityScore(qualityScore);

        return businessRepository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        if (!businessRepository.existsById(id)) {
            throw ApiException.notFound("Business not found with id: " + id);
        }
        businessRepository.deleteById(id);
    }

    /**
     * Result of an import upsert: the persisted business and whether it was newly created.
     */
    public record UpsertResult(Business business, boolean created) {}

    /**
     * Find-or-create a business coming from an import/scraper.
     *
     * Runs in its own transaction (REQUIRES_NEW) so each record commits or rolls back on its own:
     * a single bad record (constraint violation, unexpected data) can never mark the import's
     * other records rollback-only. Callers (ImportJobRunner) are not transactional and catch
     * exceptions per record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertResult importRecord(Business business) {
        // Normalize candidate data BEFORE duplicate detection
        normalizeBusinessData(business);

        // Check by external ID first
        if (business.getExternalId() != null && business.getDataSource() != null) {
            Optional<Business> existing = businessRepository
                    .findFirstByExternalIdAndDataSourceOrderByCreatedAtAsc(business.getExternalId(), business.getDataSource());
            if (existing.isPresent()) {
                log.debug("Found existing business by external ID: {}", existing.get().getId());
                return new UpsertResult(updateExisting(existing.get(), business), false);
            }
        }

        // Check for duplicates (data is already normalized)
        Optional<Business> duplicate = findDuplicate(business);
        if (duplicate.isPresent()) {
            log.debug("Found duplicate business: {}", duplicate.get().getId());
            return new UpsertResult(updateExisting(duplicate.get(), business), false);
        }

        // Create new business
        business.setDataQualityScore(dataQualityService.calculateQualityScore(business));
        return new UpsertResult(businessRepository.save(business), true);
    }

    /**
     * Would {@link #importRecord} merge this candidate into an existing lead (same external id, or one
     * of the duplicate rules)? Used by file imports that must skip duplicates instead of updating them.
     * Normalizes the candidate in place, as importRecord does.
     */
    @Transactional(readOnly = true)
    public boolean existsDuplicate(Business candidate) {
        normalizeBusinessData(candidate);
        if (candidate.getExternalId() != null && candidate.getDataSource() != null
                && businessRepository.findFirstByExternalIdAndDataSourceOrderByCreatedAtAsc(
                        candidate.getExternalId(), candidate.getDataSource()).isPresent()) {
            return true;
        }
        return findDuplicate(candidate).isPresent();
    }

    /**
     * Update existing business with new data
     * Accepts fresher non-null values to keep data current
     */
    private Business updateExisting(Business existing, Business newData) {
        // Update with fresher non-null values
        boolean shouldUpdate = false;

        // Update phone if new data has it (fills null or updates existing)
        if (newData.getPhone() != null && !newData.getPhone().equals(existing.getPhone())) {
            existing.setPhone(newData.getPhone());
            shouldUpdate = true;
        }

        // Update email if new data has it (fills null or updates existing)
        if (newData.getEmail() != null && !newData.getEmail().equals(existing.getEmail())) {
            existing.setEmail(newData.getEmail());
            shouldUpdate = true;
        }

        // Update website if new data has it (fills null or updates existing)
        if (newData.getWebsite() != null && !newData.getWebsite().equals(existing.getWebsite())) {
            existing.setWebsite(newData.getWebsite());
            shouldUpdate = true;
        }

        // Update geocoding if new data has it (fills null or updates existing)
        // Only update if both latitude and longitude are present
        if (newData.getLatitude() != null && newData.getLongitude() != null) {
            // Use null-safe comparison
            boolean latitudeChanged = !Objects.equals(newData.getLatitude(), existing.getLatitude());
            boolean longitudeChanged = !Objects.equals(newData.getLongitude(), existing.getLongitude());
            if (latitudeChanged || longitudeChanged) {
                existing.setLatitude(newData.getLatitude());
                existing.setLongitude(newData.getLongitude());
                shouldUpdate = true;
            }
        }

        // Update address fields if new data has them
        if (newData.getAddressStreet() != null && !newData.getAddressStreet().equals(existing.getAddressStreet())) {
            existing.setAddressStreet(newData.getAddressStreet());
            shouldUpdate = true;
        }

        if (newData.getPostalCode() != null && !newData.getPostalCode().equals(existing.getPostalCode())) {
            existing.setPostalCode(newData.getPostalCode());
            shouldUpdate = true;
        }

        // Source facts (permit, register...) are merged so a lead matched by several sources keeps
        // all of them; the newest value wins per key.
        if (newData.getSourceDetails() != null && !newData.getSourceDetails().isEmpty()) {
            java.util.Map<String, String> merged = existing.getSourceDetails() != null
                    ? new java.util.LinkedHashMap<>(existing.getSourceDetails())
                    : new java.util.LinkedHashMap<>();
            merged.putAll(newData.getSourceDetails());
            if (!merged.equals(existing.getSourceDetails())) {
                existing.setSourceDetails(merged);
                shouldUpdate = true;
            }
        }

        if (shouldUpdate) {
            normalizeBusinessData(existing);
            existing.setLastVerified(LocalDateTime.now());
            // Recalculate quality score after updating fields and lastVerified
            existing.setDataQualityScore(dataQualityService.calculateQualityScore(existing));
            return businessRepository.save(existing);
        }

        // Even if no fields changed, update lastVerified to mark as recently scraped
        // This affects data quality score, so recalculate it
        existing.setLastVerified(LocalDateTime.now());
        existing.setDataQualityScore(dataQualityService.calculateQualityScore(existing));
        return businessRepository.save(existing);
    }

    /**
     * Find duplicate business
     */
    public Optional<Business> findDuplicate(Business candidate) {
        return findDuplicateExcluding(candidate, null);
    }

    /**
     * Find duplicate business, excluding a specific ID (for updates)
     */
    private Optional<Business> findDuplicateExcluding(Business candidate, UUID excludeId) {
        // 1. Exact match on name and city
        if (candidate.getBusinessName() != null && candidate.getAddressCity() != null) {
            Optional<Business> exact = businessRepository
                    .findTop10ByBusinessNameAndAddressCityOrderByCreatedAtAsc(candidate.getBusinessName(), candidate.getAddressCity())
                    .stream()
                    .filter(b -> !b.getId().equals(excludeId))
                    .findFirst();
            if (exact.isPresent()) {
                return exact;
            }
        }

        // 2. Phone number match. Chains often share one head-office number across many branches,
        //    so a phone match only counts as a duplicate when the cities are compatible (same city,
        //    or one side has no city). Otherwise distinct branches would be merged into one lead and
        //    have their address overwritten.
        if (candidate.getPhone() != null) {
            String normalizedPhone = PhoneFormatter.normalize(candidate.getPhone());
            if (normalizedPhone != null && !normalizedPhone.isEmpty()) {
                Optional<Business> phoneMatch = businessRepository
                        .findTop20ByPhoneNormalizedOrderByCreatedAtAsc(normalizedPhone)
                        .stream()
                        .filter(b -> !b.getId().equals(excludeId))
                        .filter(b -> citiesCompatible(candidate.getAddressCity(), b.getAddressCity()))
                        .findFirst();
                if (phoneMatch.isPresent()) {
                    return phoneMatch;
                }
            }
        }

        // 3. Fuzzy name matching (Levenshtein distance)
        return findFuzzyDuplicate(candidate, excludeId);
    }

    private static boolean citiesCompatible(String a, String b) {
        return a == null || b == null || a.equalsIgnoreCase(b);
    }

    static final double FUZZY_THRESHOLD = 0.85;
    static final int FUZZY_PREFIX_LENGTH = 3;
    static final int FUZZY_MAX_CANDIDATES = 200;

    /**
     * Fuzzy duplicate check, bounded so imports stay roughly O(n) instead of O(n^2).
     *
     * Previously every record loaded *all* businesses with the same city + type (thousands of rows
     * for e.g. "Restaurant" in Montreal) and ran Levenshtein against each. We now block candidates
     * in the database before comparing:
     *  - same city and type (as before);
     *  - lower-cased name starts with the same first 3 characters ("blocking key"). Near-identical
     *    names almost always share their beginning; a typo in the first 3 characters will no longer
     *    be caught, which is an accepted trade-off;
     *  - name length within [0.85 * len, len / 0.85]. This filter is exact, not heuristic: the
     *    Levenshtein distance is at least the length difference, so names outside this range can
     *    never reach the 0.85 similarity threshold;
     *  - at most 200 candidates, oldest first (deterministic), as a hard cap.
     */
    private Optional<Business> findFuzzyDuplicate(Business candidate, UUID excludeId) {
        String name = candidate.getBusinessName();
        if (candidate.getAddressCity() == null || candidate.getBusinessType() == null || name == null) {
            return Optional.empty();
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return Optional.empty();
        }
        int len = lower.length();
        int minLength = (int) Math.floor(len * FUZZY_THRESHOLD);
        int maxLength = (int) Math.ceil(len / FUZZY_THRESHOLD);
        String prefix = lower.substring(0, Math.min(FUZZY_PREFIX_LENGTH, len));
        String likePattern = escapeLike(prefix) + "%";

        List<Business> candidates = businessRepository.findFuzzyCandidates(
                candidate.getAddressCity(), candidate.getBusinessType(), likePattern,
                minLength, maxLength, PageRequest.of(0, FUZZY_MAX_CANDIDATES));

        for (Business existing : candidates) {
            if (excludeId != null && existing.getId().equals(excludeId)) {
                continue;
            }
            double similarity = StringSimilarity.calculate(name, existing.getBusinessName());
            if (similarity > FUZZY_THRESHOLD) {
                log.debug("Found fuzzy match with similarity: {}", similarity);
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * Normalize business data (format phone, address, postal code)
     */
    private void normalizeBusinessData(Business business) {
        // Format phone number for display and set normalized version for duplicate detection
        if (business.getPhone() != null) {
            business.setPhone(PhoneFormatter.format(business.getPhone()));
            business.setPhoneNormalized(PhoneFormatter.normalize(business.getPhone()));
        }

        // Format postal code
        if (business.getPostalCode() != null) {
            business.setPostalCode(PostalCodeValidator.format(business.getPostalCode()));
        }

        // Normalize address
        if (business.getAddressStreet() != null) {
            business.setAddressStreet(AddressNormalizer.normalizeStreet(business.getAddressStreet()));
        }

        if (business.getAddressCity() != null) {
            business.setAddressCity(AddressNormalizer.normalizeCity(business.getAddressCity()));
        }

        // Ensure province is uppercase
        if (business.getAddressProvince() != null) {
            business.setAddressProvince(business.getAddressProvince().toUpperCase());
        }

        // Trim whitespace
        if (business.getBusinessName() != null) {
            business.setBusinessName(business.getBusinessName().trim());
        }
        if (business.getEmail() != null) {
            business.setEmail(business.getEmail().trim().toLowerCase());
        }
    }

    @Transactional(readOnly = true)
    public List<Business> findStaleBusinesses(int days) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(days);
        return businessRepository.findStaleBusinesses(cutoffDate);
    }

    @Transactional(readOnly = true)
    public Long countByBusinessType(String businessType) {
        return businessRepository.countByBusinessType(businessType);
    }

    @Transactional(readOnly = true)
    public Long countByDataSource(String dataSource) {
        return businessRepository.countByDataSource(dataSource);
    }
}
