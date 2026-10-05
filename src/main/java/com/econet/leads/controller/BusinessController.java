package com.econet.leads.controller;

import com.econet.leads.dto.BusinessCreateRequest;
import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.BusinessFilterDTO;
import com.econet.leads.dto.BusinessFilterOptionsDTO;
import com.econet.leads.dto.BusinessUpdateRequest;
import com.econet.leads.dto.LeadStatusUpdateRequest;
import com.econet.leads.exception.ApiException;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.LeadService;
import com.opencsv.CSVWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@RestController
@RequestMapping("/api/businesses")
@RequiredArgsConstructor
@Tag(name = "Business", description = "Business/Prospect management endpoints")
public class BusinessController {

    private static final DateTimeFormatter CSV_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BusinessService businessService;
    private final BusinessCategoryRepository businessCategoryRepository;
    private final UserRepository userRepository;
    private final LeadService leadService;
    private final AuthenticationFacade authenticationFacade;
    private final Clock clock;

    static final Set<String> SORTABLE_FIELDS = Set.of(
            "createdAt", "businessName", "dataQualityScore", "lastContactedAt", "nextFollowUpAt", "addressCity");
    static final int MAX_PAGE_SIZE = 500;
    static final int EXPORT_BATCH_SIZE = 1000;

    @GetMapping
    @Operation(summary = "Get all businesses", description = "Paginated list of leads with optional filters (q, leadStatus, businessType, city, dataSource, hasPhone, assignedTo, minQualityScore, followUpDue)")
    public ResponseEntity<Page<BusinessDTO>> getAllBusinesses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection,
            @ModelAttribute BusinessFilterDTO filters) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                buildSort(sortBy, sortDirection));
        return ResponseEntity.ok(businessService.findDtos(buildSpecification(filters), pageable));
    }

    @GetMapping("/filters")
    @Operation(summary = "Filter options", description = "Distinct business types, cities (top 200 by count) and data sources, sorted")
    public ResponseEntity<BusinessFilterOptionsDTO> getFilterOptions() {
        return ResponseEntity.ok(businessService.getFilterOptions());
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export leads as CSV", description = "Same filters as the list; UTF-8 with BOM so Excel shows accents correctly")
    public void exportCsv(@RequestParam(defaultValue = "createdAt") String sortBy,
                          @RequestParam(defaultValue = "DESC") String sortDirection,
                          @ModelAttribute BusinessFilterDTO filters,
                          HttpServletResponse response) throws IOException {
        Specification<Business> spec = buildSpecification(filters);
        Sort sort = buildSort(sortBy, sortDirection);

        String filename = "leads-" + LocalDate.now(clock) + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");

        OutputStream out = response.getOutputStream();
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM for Excel
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.writeNext(new String[]{"name", "type", "phone", "email", "website", "street", "city",
                    "postal_code", "status", "last_contacted", "next_follow_up", "quality", "source"}, false);
            int pageIndex = 0;
            Page<BusinessDTO> batch;
            do {
                batch = businessService.findDtos(spec, PageRequest.of(pageIndex++, EXPORT_BATCH_SIZE, sort));
                for (BusinessDTO b : batch.getContent()) {
                    writer.writeNext(new String[]{
                            b.getBusinessName(), b.getBusinessType(), b.getPhone(), b.getEmail(), b.getWebsite(),
                            b.getAddressStreet(), b.getAddressCity(), b.getPostalCode(),
                            b.getLeadStatus() != null ? b.getLeadStatus().name() : null,
                            b.getLastContactedAt() != null ? b.getLastContactedAt().format(CSV_DATE_TIME) : null,
                            b.getNextFollowUpAt() != null ? b.getNextFollowUpAt().format(CSV_DATE_TIME) : null,
                            b.getDataQualityScore() != null ? b.getDataQualityScore().toString() : null,
                            b.getDataSource()
                    }, true);
                }
                writer.flush();
            } while (batch.hasNext());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get business by ID", description = "Get detailed information about a specific business")
    public ResponseEntity<BusinessDTO> getBusinessById(@PathVariable UUID id) {
        return businessService.findDtoById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ApiException.notFound("Business not found with id: " + id));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change lead status", description = "Set the pipeline status; recorded in the lead history with the optional note")
    public ResponseEntity<BusinessDTO> updateStatus(@PathVariable UUID id,
                                                    @Valid @RequestBody LeadStatusUpdateRequest request) {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(leadService.updateStatus(id, request, user.getId()));
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
                .orElseThrow(() -> ApiException.badRequest("Category not found with id: " + request.getCategoryId()));
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
                .orElseThrow(() -> ApiException.badRequest("Category not found with id: " + request.getCategoryId()));
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
        business.setEstimatedValue(request.getEstimatedValue());
        if (request.getAssignedToId() != null) {
            business.setAssignedTo(userRepository.findById(request.getAssignedToId())
                    .orElseThrow(() -> ApiException.badRequest("User not found: " + request.getAssignedToId())));
        }

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
        return DtoMapper.toDto(business);
    }

    private Specification<Business> buildSpecification(BusinessFilterDTO filters) {
        String assignedTo = filters.getAssignedTo();
        filters.setAssignedToUserId(null);
        if (assignedTo != null && !assignedTo.isBlank()) {
            if ("me".equalsIgnoreCase(assignedTo.trim())) {
                filters.setAssignedToUserId(authenticationFacade.getCurrentUser().getId());
            } else {
                try {
                    filters.setAssignedToUserId(UUID.fromString(assignedTo.trim()));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("assignedTo must be a user id or 'me'");
                }
            }
        }
        return BusinessSpecification.withFilters(filters, LocalDateTime.now(clock));
    }

    static Sort buildSort(String sortBy, String sortDirection) {
        if (!SORTABLE_FIELDS.contains(sortBy)) {
            throw ApiException.badRequest("sortBy must be one of " + new TreeSet<>(SORTABLE_FIELDS));
        }
        Sort.Direction direction;
        if ("ASC".equalsIgnoreCase(sortDirection)) {
            direction = Sort.Direction.ASC;
        } else if ("DESC".equalsIgnoreCase(sortDirection)) {
            direction = Sort.Direction.DESC;
        } else {
            throw ApiException.badRequest("sortDirection must be ASC or DESC");
        }
        // id as tie-breaker keeps pagination stable
        return Sort.by(direction, sortBy).and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
