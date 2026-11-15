package com.econet.leads.integration.website;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published (as a Spring event) next to the {@code lead.inbound} SSE broadcast each time a website
 * lead is announced ({@code backlog}: an old request imported late, e.g. on a first sync, which must not alert the owner), so other features (owner alerts) can react without touching the importer.
 */
public record InboundLeadEvent(UUID leadId, String leadName, String kind, String businessType,
                               BigDecimal estimatedValue, String phone, boolean backlog) {

    public InboundLeadEvent(UUID leadId, String leadName, String kind, String businessType,
                            BigDecimal estimatedValue, String phone) {
        this(leadId, leadName, kind, businessType, estimatedValue, phone, false);
    }
}
