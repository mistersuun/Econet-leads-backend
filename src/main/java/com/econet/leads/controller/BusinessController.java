package com.econet.leads.controller;

import com.econet.leads.dto.BusinessCreateRequest;
import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.BusinessFilterDTO;
import com.econet.leads.dto.BusinessUpdateRequest;
import com.econet.leads.model.Business;
import com.econet.leads.model.BusinessCategory;
import com.econet.leads.repository.BusinessCategoryRepository;
import com.econet.leads.repository.BusinessSpecification;
import com.econet.leads.service.BusinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/businesses")
@RequiredArgsConstructor
@Tag(name = "Business", description = "Business/Prospect management endpoints")
public class BusinessController {

    private final BusinessService businessService;
    private final BusinessCategoryRepository businessCategoryRepository;

    @GetMapping
    @Operation(summary = "Get all businesses", description = "Get paginated list of businesses with optional filters")
    public ResponseEntity<Page<BusinessDTO>> getAllBusinesses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @ModelAttribute BusinessFilterDTO filters) {

        Sort sort = sortDirection.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Business> businesses;
        if (hasFilters(filters)) {
            businesses = businessService.findWithFilters(
                    BusinessSpecification.withFilters(filters),
                    pageable
            );
        } else {
            businesses = businessService.findAll(pageable);
        }

        Page<BusinessDTO> dtoPage = businesses.map(this::convertToDTO);
        return ResponseEntity.ok(dtoPage);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get business by ID", description = "Get detailed information about a specific business")
    public ResponseEntity<BusinessDTO> getBusinessById(@PathVariable UUID id) {
        return businessService.findById(id)
                .map(this::convertToDTO)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    @Operation(summary = "Search businesses", description = "Full-text search across business names")
    public ResponseEntity<Page<BusinessDTO>> searchBusinesses(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<BusinessDTO> results = businessService.search(q, pageable)
                .map(this::convertToDTO);
        return ResponseEntity.ok(results);
    }

    @PostMapping
    @Operation(summary = "Create business", description = "Create a new business entry")
    public ResponseEntity<BusinessDTO> createBusiness(@Valid @RequestBody BusinessCreateRequest request) {
        Business business = new Business();
        business.setBusinessName(request.getBusinessName());
        business.setBusinessType(request.getBusinessType());

        // Fetch category if provided
        if (request.getCategoryId() != null) {
            BusinessCategory category = businessCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found with id: " + request.getCategoryId()));
            business.setCategory(category);
        }

        business.setAddressStreet(request.getAddressStreet());
        business.setAddressCity(request.getAddressCity());
        business.setAddressProvince(request.getAddressProvince());
        business.setPostalCode(request.getPostalCode());
        business.setPhone(request.getPhone());
        business.setEmail(request.getEmail());
        business.setWebsite(request.getWebsite());
        business.setLatitude(request.getLatitude());
        business.setLongitude(request.getLongitude());
        business.setDataSource(request.getDataSource());
        business.setSourceUrl(request.getSourceUrl());
        business.setExternalId(request.getExternalId());

        Business created = businessService.create(business);
        return ResponseEntity.ok(convertToDTO(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update business", description = "Update an existing business")
    public ResponseEntity<BusinessDTO> updateBusiness(
            @PathVariable UUID id,
            @Valid @RequestBody BusinessUpdateRequest request) {

        Business business = new Business();
        business.setBusinessName(request.getBusinessName());
        business.setBusinessType(request.getBusinessType());

        // Fetch category if provided
        if (request.getCategoryId() != null) {
            BusinessCategory category = businessCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new RuntimeException("Category not found with id: " + request.getCategoryId()));
            business.setCategory(category);
        }

        business.setAddressStreet(request.getAddressStreet());
        business.setAddressCity(request.getAddressCity());
        business.setAddressProvince(request.getAddressProvince());
        business.setPostalCode(request.getPostalCode());
        business.setPhone(request.getPhone());
        business.setEmail(request.getEmail());
        business.setWebsite(request.getWebsite());
        business.setLatitude(request.getLatitude());
        business.setLongitude(request.getLongitude());

        Business updated = businessService.update(id, business);
        return ResponseEntity.ok(convertToDTO(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete business", description = "Delete a business (admin only)")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteBusiness(@PathVariable UUID id) {
        businessService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats/by-type")
    @Operation(summary = "Get count by type", description = "Get number of businesses by type")
    public ResponseEntity<Long> countByType(@RequestParam String type) {
        return ResponseEntity.ok(businessService.countByBusinessType(type));
    }

    @GetMapping("/stats/by-source")
    @Operation(summary = "Get count by source", description = "Get number of businesses by data source")
    public ResponseEntity<Long> countBySource(@RequestParam String source) {
        return ResponseEntity.ok(businessService.countByDataSource(source));
    }

    private BusinessDTO convertToDTO(Business business) {
        BusinessDTO dto = new BusinessDTO();
        dto.setId(business.getId());
        dto.setBusinessName(business.getBusinessName());
        dto.setBusinessType(business.getBusinessType());
        dto.setCategoryId(business.getCategory() != null ? business.getCategory().getId() : null);
        dto.setAddressStreet(business.getAddressStreet());
        dto.setAddressCity(business.getAddressCity());
        dto.setAddressProvince(business.getAddressProvince());
        dto.setPostalCode(business.getPostalCode());
        dto.setPhone(business.getPhone());
        dto.setEmail(business.getEmail());
        dto.setWebsite(business.getWebsite());
        dto.setLatitude(business.getLatitude());
        dto.setLongitude(business.getLongitude());
        dto.setDataSource(business.getDataSource());
        dto.setSourceUrl(business.getSourceUrl());
        dto.setCreatedAt(business.getCreatedAt());
        dto.setUpdatedAt(business.getUpdatedAt());
        dto.setLastVerified(business.getLastVerified());
        dto.setDataQualityScore(business.getDataQualityScore());
        dto.setFullAddress(business.getFullAddress());
        return dto;
    }

    private boolean hasFilters(BusinessFilterDTO filters) {
        return filters.getBusinessType() != null ||
               filters.getCity() != null ||
               filters.getProvince() != null ||
               filters.getDataSource() != null ||
               filters.getSearchTerm() != null ||
               filters.getMinQualityScore() != null ||
               filters.getMaxQualityScore() != null ||
               (filters.getStale() != null && filters.getStale()) ||
               filters.getContactStatus() != null;
    }
}
