package com.econet.leads.controller;

import com.econet.leads.dto.ContactCreateRequest;
import com.econet.leads.dto.ContactDTO;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Contact;
import com.econet.leads.model.User;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
@Tag(name = "Contact", description = "Contact tracking and CRM endpoints")
public class ContactController {

    private final ContactService contactService;
    private final AuthenticationFacade authenticationFacade;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Get all contacts", description = "Get paginated list of contacts. Users see only their own contacts, admins see all.")
    public ResponseEntity<Page<ContactDTO>> getAllContacts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        User currentUser = authenticationFacade.getCurrentUser();
        Pageable pageable = PageRequest.of(page, size);
        
        // Filter by current user unless admin
        Page<ContactDTO> contacts;
        if (currentUser.getRole() == User.UserRole.ADMIN) {
            contacts = contactService.findAll(pageable).map(this::convertToDTO);
        } else {
            contacts = contactService.findByUserId(currentUser.getId(), pageable).map(this::convertToDTO);
        }
        
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    @Operation(summary = "Get contact by ID", description = "Get detailed information about a specific contact")
    public ResponseEntity<ContactDTO> getContactById(@PathVariable UUID id) {
        User currentUser = authenticationFacade.getCurrentUser();

        return contactService.findByIdWithAssociations(id)
                .map(contact -> {
                    // Verify ownership - users can only view their own contacts unless admin
                    if (!contact.getUser().getId().equals(currentUser.getId()) &&
                        currentUser.getRole() != User.UserRole.ADMIN) {
                        throw new AccessDeniedException("Access denied: You can only view your own contacts");
                    }
                    return convertToDTO(contact);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/business/{businessId}")
    @Transactional(readOnly = true)
    @Operation(summary = "Get contacts for business", description = "Get contact history for a specific business. Users see only their own contacts, admins see all.")
    public ResponseEntity<List<ContactDTO>> getContactsByBusiness(@PathVariable UUID businessId) {
        User currentUser = authenticationFacade.getCurrentUser();

        // Filter contacts by current user unless admin - filter instead of throwing to allow shared accounts
        List<ContactDTO> contacts = contactService.findByBusinessId(businessId).stream()
                .filter(contact -> {
                    // Admins can see all contacts, users can only see their own
                    return currentUser.getRole() == User.UserRole.ADMIN ||
                           contact.getUser().getId().equals(currentUser.getId());
                })
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/user/{userId}")
    @Transactional(readOnly = true)
    @Operation(summary = "Get contacts by user", description = "Get all contacts created by a specific user")
    public ResponseEntity<Page<ContactDTO>> getContactsByUser(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        User currentUser = authenticationFacade.getCurrentUser();

        // Authorization check: users can only view their own contacts unless admin
        if (!userId.equals(currentUser.getId()) && currentUser.getRole() != User.UserRole.ADMIN) {
            throw new AccessDeniedException("Access denied: You can only view your own contacts");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<ContactDTO> contacts = contactService.findByUserId(userId, pageable)
                .map(this::convertToDTO);
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/upcoming")
    @Transactional(readOnly = true)
    @Operation(summary = "Get upcoming follow-ups", description = "Get contacts with upcoming action dates. Users see only their own contacts, admins see all.")
    public ResponseEntity<List<ContactDTO>> getUpcomingFollowUps(
            @RequestParam(defaultValue = "7") int days) {

        User currentUser = authenticationFacade.getCurrentUser();
        LocalDateTime startDate = LocalDateTime.now();
        LocalDateTime endDate = startDate.plusDays(days);

        List<ContactDTO> contacts = contactService.findUpcomingFollowUps(startDate, endDate).stream()
                .filter(contact -> {
                    // Admins can see all contacts, users can only see their own
                    return currentUser.getRole() == User.UserRole.ADMIN ||
                           contact.getUser().getId().equals(currentUser.getId());
                })
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/recent")
    @Transactional(readOnly = true)
    @Operation(summary = "Get recent contacts", description = "Get recently created contacts. Users see only their own contacts, admins see all.")
    public ResponseEntity<List<ContactDTO>> getRecentContacts(
            @RequestParam(defaultValue = "7") int days) {

        User currentUser = authenticationFacade.getCurrentUser();

        List<ContactDTO> contacts = contactService.findRecentContacts(days).stream()
                .filter(contact -> {
                    // Admins can see all contacts, users can only see their own
                    return currentUser.getRole() == User.UserRole.ADMIN ||
                           contact.getUser().getId().equals(currentUser.getId());
                })
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(contacts);
    }

    @PostMapping
    @Transactional
    @Operation(summary = "Create contact", description = "Create a new contact record")
    public ResponseEntity<ContactDTO> createContact(@Valid @RequestBody ContactCreateRequest request) {
        // Get current authenticated user - always derive from JWT for auditability
        User currentUser = authenticationFacade.getCurrentUser();

        Contact contact = new Contact();
        contact.setContactDate(request.getContactDate() != null ? request.getContactDate() : LocalDateTime.now());
        contact.setContactType(request.getContactType());
        contact.setContactStatus(request.getContactStatus());
        contact.setContactPerson(request.getContactPerson());
        contact.setNotes(request.getNotes());
        contact.setNextAction(request.getNextAction());
        contact.setNextActionDate(request.getNextActionDate());
        contact.setOutcome(request.getOutcome());

        Contact created = contactService.create(contact, request.getBusinessId(), currentUser.getId());
        return ResponseEntity.ok(convertToDTO(created));
    }

    @PutMapping("/{id}")
    @Transactional
    @Operation(summary = "Update contact", description = "Update an existing contact")
    public ResponseEntity<ContactDTO> updateContact(
            @PathVariable UUID id,
            @Valid @RequestBody ContactCreateRequest request) {

        User currentUser = authenticationFacade.getCurrentUser();

        // Verify ownership before update - load with associations to avoid lazy loading issues
        Contact existing = contactService.findByIdWithAssociations(id)
                .orElseThrow(() -> com.econet.leads.exception.ApiException.notFound("Contact not found with id: " + id));

        if (!existing.getUser().getId().equals(currentUser.getId()) &&
            currentUser.getRole() != User.UserRole.ADMIN) {
            throw new AccessDeniedException("Access denied: You can only update your own contacts");
        }

        Contact contact = new Contact();
        contact.setContactDate(request.getContactDate());
        contact.setContactType(request.getContactType());
        contact.setContactStatus(request.getContactStatus());
        contact.setContactPerson(request.getContactPerson());
        contact.setNotes(request.getNotes());
        contact.setNextAction(request.getNextAction());
        contact.setNextActionDate(request.getNextActionDate());
        contact.setOutcome(request.getOutcome());

        Contact updated = contactService.update(id, contact);
        return ResponseEntity.ok(convertToDTO(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete contact", description = "Delete a contact record")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteContact(@PathVariable UUID id) {
        // Only admins can delete contacts (as per SecurityConfig line 51)
        contactService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private ContactDTO convertToDTO(Contact contact) {
        return DtoMapper.toDto(contact);
    }
}
