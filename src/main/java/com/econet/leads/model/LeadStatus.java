package com.econet.leads.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Sales pipeline status of a lead (business). Declaration order is the pipeline order.
 */
public enum LeadStatus {
    NEW,
    CONTACTED,
    INTERESTED,
    QUOTE_SENT,
    WON,
    LOST,
    DO_NOT_CALL;

    /** Statuses for which no further calls are expected. */
    public static final Set<LeadStatus> TERMINAL = EnumSet.of(WON, LOST, DO_NOT_CALL);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
