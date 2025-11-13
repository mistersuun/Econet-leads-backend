package com.econet.leads.service;

import com.econet.leads.model.Business;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.util.AddressNormalizer;
import com.econet.leads.util.PhoneFormatter;
import com.econet.leads.util.PostalCodeValidator;
import com.econet.leads.util.StringSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final DataQualityService dataQualityService;

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
            throw new RuntimeException("Duplicate business already exists: " + duplicate.get().getBusinessName());
        }

        // Calculate quality score
        int qualityScore = dataQualityService.calculateQualityScore(business);
        business.setDataQualityScore(qualityScore);

        return businessRepository.save(business);
    }

    @Transactional
    public Business update(UUID id, Business updatedBusiness) {
        Business existing = businessRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Business not found with id: " + id));

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

        // Immutable fields - cannot be changed after creation
        // dataSource, externalId, sourceUrl are locked down

        // Normalize data
        normalizeBusinessData(existing);

        // Check for duplicates (excluding current business)
        Optional<Business> duplicate = findDuplicateExcluding(existing, id);
        if (duplicate.isPresent()) {
            log.warn("Update would create duplicate: {}", duplicate.get().getId());
            throw new RuntimeException("Update rejected: would create duplicate of business: " + duplicate.get().getBusinessName());
        }

        // Recalculate quality score
        int qualityScore = dataQualityService.calculateQualityScore(existing);
        existing.setDataQualityScore(qualityScore);

        return businessRepository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        if (!businessRepository.existsById(id)) {
            throw new RuntimeException("Business not found with id: " + id);
        }
        businessRepository.deleteById(id);
    }

    /**
     * Find or create business (for imports/scraping)
     */
    @Transactional
    public Business findOrCreate(Business business) {
        // Normalize candidate data BEFORE duplicate detection
        normalizeBusinessData(business);

        // Check by external ID first
        if (business.getExternalId() != null && business.getDataSource() != null) {
            Optional<Business> existing = businessRepository
                    .findByExternalIdAndDataSource(business.getExternalId(), business.getDataSource());
            if (existing.isPresent()) {
                log.debug("Found existing business by external ID: {}", existing.get().getId());
                return updateExisting(existing.get(), business);
            }
        }

        // Check for duplicates (data is already normalized)
        Optional<Business> duplicate = findDuplicate(business);
        if (duplicate.isPresent()) {
            log.debug("Found duplicate business: {}", duplicate.get().getId());
            return updateExisting(duplicate.get(), business);
        }

        // Create new business
        business.setDataQualityScore(dataQualityService.calculateQualityScore(business));
        return businessRepository.save(business);
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
                    .findByBusinessNameAndAddressCity(candidate.getBusinessName(), candidate.getAddressCity());
            if (exact.isPresent() && !exact.get().getId().equals(excludeId)) {
                return exact;
            }
        }

        // 2. Phone number match
        if (candidate.getPhone() != null) {
            String normalizedPhone = PhoneFormatter.normalize(candidate.getPhone());
            Optional<Business> phoneMatch = businessRepository.findByPhoneNormalized(normalizedPhone);
            if (phoneMatch.isPresent() && !phoneMatch.get().getId().equals(excludeId)) {
                return phoneMatch;
            }
        }

        // 3. Fuzzy name matching (Levenshtein distance)
        if (candidate.getAddressCity() != null && candidate.getBusinessType() != null) {
            List<Business> similar = businessRepository
                    .findByAddressCityAndBusinessType(candidate.getAddressCity(), candidate.getBusinessType());

            for (Business existing : similar) {
                // Skip if this is the business being updated
                if (excludeId != null && existing.getId().equals(excludeId)) {
                    continue;
                }

                double similarity = StringSimilarity.calculate(
                        candidate.getBusinessName(),
                        existing.getBusinessName()
                );
                if (similarity > 0.85) {
                    log.debug("Found fuzzy match with similarity: {}", similarity);
                    return Optional.of(existing);
                }
            }
        }

        return Optional.empty();
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
