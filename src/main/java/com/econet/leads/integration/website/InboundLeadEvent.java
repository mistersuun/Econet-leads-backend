package com.econet.leads.integration.website;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published (as a Spring event) next to the {@code lead.inbound} SSE broadcast each time a website
 * lead is announced, so other features (owner alerts) can react without touching the importer.
 */
public record InboundLeadEvent(UUID leadId, String leadName, String kind, String businessType,
                               BigDecimal estimatedValue, String phone) {
}
