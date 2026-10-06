package com.econet.leads.model;

/**
 * Follow-up status of a public tender, set by the team (imports never change it).
 */
public enum TenderStatus {
    NEW,
    REVIEWING,
    BIDDING,
    SUBMITTED,
    WON,
    LOST,
    IGNORED
}
