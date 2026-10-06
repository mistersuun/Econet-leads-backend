package com.econet.leads.service;

import com.econet.leads.model.Business;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContactService {

    private final ContactRepository contactRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<Contact> findAll(Pageable pageable) {
        return contactRepository.findAllWithAssociations(pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Contact> findById(UUID id) {
        return contactRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Contact> findByIdWithAssociations(UUID id) {
        return contactRepository.findByIdWithAssociations(id);
    }

    @Transactional(readOnly = true)
    public List<Contact> findByBusinessId(UUID businessId) {
        return contactRepository.findByBusinessIdOrderByContactDateDesc(businessId);
    }

    @Transactional(readOnly = true)
    public Page<Contact> findByUserId(UUID userId, Pageable pageable) {
        return contactRepository.findByUserIdOrderByContactDateDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Contact> findUpcomingFollowUps(LocalDateTime startDate, LocalDateTime endDate) {
        return contactRepository.findUpcomingFollowUps(startDate, endDate);
    }

    @Transactional(readOnly = true)
    public List<Contact> findRecentContacts(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return contactRepository.findRecentContacts(since);
    }

    @Transactional
    public Contact create(Contact contact, UUID businessId, UUID userId) {
        // Validate business exists
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> ApiException.notFound("Business not found with id: " + businessId));

        // Validate user exists
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found with id: " + userId));

        contact.setBusiness(business);
        contact.setUser(user);

        if (contact.getContactDate() == null) {
            contact.setContactDate(LocalDateTime.now());
        }

        Contact saved = contactRepository.save(contact);

        // Keep the lead's denormalized pipeline counters in sync for contacts logged through the
        // generic contacts API (calls logged via /api/leads/{id}/calls do this in LeadService).
        if (contact.getContactType() != Contact.ContactType.NOTE) {
            business.setContactCount((business.getContactCount() != null ? business.getContactCount() : 0) + 1);
            if (business.getLastContactedAt() == null || contact.getContactDate().isAfter(business.getLastContactedAt())) {
                business.setLastContactedAt(contact.getContactDate());
            }
            if (business.getLeadStatus() == LeadStatus.NEW) {
                business.setLeadStatus(LeadStatus.CONTACTED);
            }
            businessRepository.save(business);
        }

        // Initialize associations to avoid LazyInitializationException
        // Access them within transaction to trigger lazy loading
        saved.getBusiness().getBusinessName();
        saved.getUser().getUsername();
        return saved;
    }

    /**
     * Update contact with PATCH semantics - only update non-null fields
     * This allows partial updates without violating NOT NULL constraints
     */
    @Transactional
    public Contact update(UUID id, Contact updatedContact) {
        // Fetch with associations to avoid LazyInitializationException
        Contact existing = contactRepository.findByIdWithAssociations(id)
                .orElseThrow(() -> ApiException.notFound("Contact not found with id: " + id));

        // Only update fields that are provided (non-null)
        if (updatedContact.getContactDate() != null) {
            existing.setContactDate(updatedContact.getContactDate());
        }
        if (updatedContact.getContactType() != null) {
            existing.setContactType(updatedContact.getContactType());
        }
        if (updatedContact.getContactStatus() != null) {
            existing.setContactStatus(updatedContact.getContactStatus());
        }
        // These fields can be null, so always update them
        existing.setContactPerson(updatedContact.getContactPerson());
        existing.setNotes(updatedContact.getNotes());
        existing.setNextAction(updatedContact.getNextAction());
        existing.setNextActionDate(updatedContact.getNextActionDate());
        if (updatedContact.getOutcome() != null) {
            existing.setOutcome(updatedContact.getOutcome());
        }

        Contact saved = contactRepository.save(existing);
        // Associations are already loaded, but ensure they're accessible
        saved.getBusiness().getBusinessName();
        saved.getUser().getUsername();
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        if (!contactRepository.existsById(id)) {
            throw ApiException.notFound("Contact not found with id: " + id);
        }
        contactRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public Long countByUserId(UUID userId) {
        return contactRepository.countByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Long countByStatus(String status) {
        return contactRepository.countByContactStatus(status);
    }
}
