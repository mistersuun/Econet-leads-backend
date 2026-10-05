package com.econet.leads.mapper;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.ContactDTO;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.User;

/**
 * Entity -> DTO mapping shared by controllers and services.
 * Contact mapping touches the lazy business/user associations: call it inside a transaction or
 * on entities loaded with a fetch join.
 */
public final class DtoMapper {

    private DtoMapper() {
    }

    public static BusinessDTO toDto(Business business) {
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
        dto.setExternalId(business.getExternalId());
        dto.setSourceUrl(business.getSourceUrl());
        dto.setCreatedAt(business.getCreatedAt());
        dto.setUpdatedAt(business.getUpdatedAt());
        dto.setLastVerified(business.getLastVerified());
        dto.setDataQualityScore(business.getDataQualityScore());
        dto.setFullAddress(business.getFullAddress());

        dto.setLeadStatus(business.getLeadStatus());
        User assignee = business.getAssignedTo();
        dto.setAssignedToId(assignee != null ? assignee.getId() : null);
        dto.setAssignedToName(assignee != null ? assignee.getUsername() : null);
        dto.setLastContactedAt(business.getLastContactedAt());
        dto.setNextFollowUpAt(business.getNextFollowUpAt());
        dto.setContactCount(business.getContactCount() != null ? business.getContactCount() : 0);
        dto.setEstimatedValue(business.getEstimatedValue());
        return dto;
    }

    public static ContactDTO toDto(Contact contact) {
        ContactDTO dto = new ContactDTO();
        dto.setId(contact.getId());
        dto.setBusinessId(contact.getBusiness().getId());
        dto.setBusinessName(contact.getBusiness().getBusinessName());
        dto.setContactDate(contact.getContactDate());
        dto.setContactType(contact.getContactType());
        dto.setContactStatus(contact.getContactStatus());
        dto.setContactPerson(contact.getContactPerson());
        dto.setNotes(contact.getNotes());
        dto.setNextAction(contact.getNextAction());
        dto.setNextActionDate(contact.getNextActionDate());
        dto.setUserId(contact.getUser().getId());
        dto.setUsername(contact.getUser().getUsername());
        dto.setOutcome(contact.getOutcome());
        dto.setCreatedAt(contact.getCreatedAt());
        return dto;
    }
}
