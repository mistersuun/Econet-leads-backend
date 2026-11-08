package com.econet.leads.service;

import com.econet.leads.dto.BusinessCreateRequest;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessCategoryRepository;
import com.econet.leads.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** "Ajouter un lead": one lead typed in by a person (POST /api/businesses). */
@Service
@RequiredArgsConstructor
public class ManualLeadService {

    public static final String DATA_SOURCE = "Manuel";
    public static final String DEFAULT_TYPE = "Autre";
    static final Set<LeadStatus> ALLOWED_STATUSES =
            EnumSet.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.INTERESTED, LeadStatus.QUOTE_SENT);

    private final BusinessService businessService;
    private final BusinessCategoryRepository categoryRepository;
    private final ContactRepository contactRepository;
    private final QualificationService qualificationService;
    private final Clock clock;

    /**
     * dataSource is always "Manuel", sourceDetails records who added the lead and where it came from,
     * the lead is assigned to its creator and {@code notes} become a NOTE contact.
     *
     * @throws com.econet.leads.exception.DuplicateLeadException on an exact duplicate unless force
     */
    @Transactional
    public Business create(BusinessCreateRequest request, User creator) {
        LeadStatus status = request.getLeadStatus() == null ? LeadStatus.NEW : request.getLeadStatus();
        if (!ALLOWED_STATUSES.contains(status)) {
            throw ApiException.badRequest("leadStatus must be one of " + ALLOWED_STATUSES + " when adding a lead");
        }

        Business business = new Business();
        business.setBusinessName(request.getBusinessName());
        business.setBusinessType(blankToNull(request.getBusinessType()) != null ? request.getBusinessType().trim() : DEFAULT_TYPE);
        if (request.getCategoryId() != null) {
            business.setCategory(categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> ApiException.badRequest("Category not found with id: " + request.getCategoryId())));
        }
        business.setAddressStreet(request.getAddressStreet());
        business.setAddressCity(request.getAddressCity());
        if (request.getAddressProvince() != null) {
            business.setAddressProvince(request.getAddressProvince());
        }
        business.setPostalCode(request.getPostalCode());
        business.setPhone(blankToNull(request.getPhone()));
        business.setEmail(blankToNull(request.getEmail()));
        business.setWebsite(blankToNull(request.getWebsite()));
        business.setLatitude(request.getLatitude());
        business.setLongitude(request.getLongitude());
        business.setSourceUrl(request.getSourceUrl());
        business.setExternalId(request.getExternalId());
        business.setDataSource(DATA_SOURCE);
        business.setLeadStatus(status);
        business.setAssignedTo(creator);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("Ajouté par", creator.getUsername());
        String origin = blankToNull(request.getLeadSource());
        if (origin != null) {
            details.put("Origine", origin.trim());
        }
        business.setSourceDetails(details);

        if (request.getQualification() != null && !request.getQualification().isEmpty()) {
            Map<String, Object> qualification = qualificationService.merge(null, request.getQualification());
            business.setQualification(qualification.isEmpty() ? null : qualification);
        }

        Business created = businessService.createManual(business, Boolean.TRUE.equals(request.getForce()));

        String notes = blankToNull(request.getNotes());
        if (notes != null) {
            Contact note = new Contact();
            note.setBusiness(created);
            note.setUser(creator);
            note.setContactType(Contact.ContactType.NOTE);
            note.setContactStatus("LEAD_CREATED");
            note.setContactDate(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
            note.setNotes(notes.trim());
            contactRepository.save(note);
        }
        return created;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
