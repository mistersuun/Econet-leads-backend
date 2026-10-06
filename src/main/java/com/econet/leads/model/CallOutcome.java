package com.econet.leads.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Outcome of a phone call logged through POST /api/leads/{id}/calls.
 * Stored in contacts.outcome via {@link Contact.ContactOutcome} (same names).
 */
public enum CallOutcome {
    NO_ANSWER,
    VOICEMAIL,
    CALLBACK,
    INTERESTED,
    NOT_INTERESTED,
    QUOTE_SENT,
    WON,
    WRONG_NUMBER,
    DO_NOT_CALL;

    /** Outcomes where we actually spoke with someone. */
    public static final Set<CallOutcome> CONVERSATIONS = EnumSet.of(INTERESTED, QUOTE_SENT, WON, NOT_INTERESTED, CALLBACK);

    public Contact.ContactOutcome toContactOutcome() {
        return Contact.ContactOutcome.valueOf(name());
    }
}
