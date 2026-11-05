package com.econet.leads.dto;

/** Response of POST /api/leads/{id}/emails. */
public record EmailSendResponse(ContactDTO contact, EmailPreviewDTO preview) {
}
