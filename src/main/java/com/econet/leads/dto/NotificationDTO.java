package com.econet.leads.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

/** One item of GET /api/notifications. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationDTO(String id, String kind, String title, String body, UUID leadId, UUID tenderId,
                              LocalDateTime at) {
    public static final String FOLLOW_UP_DUE = "follow_up_due";
    public static final String FOLLOW_UP_OVERDUE = "follow_up_overdue";
    public static final String CLAIM_EXPIRED = "claim_expired";
    public static final String TENDER_CLOSING = "tender_closing";
    /** A request from the website that nobody has called yet. */
    public static final String INBOUND_REQUEST = "inbound_request";
}
