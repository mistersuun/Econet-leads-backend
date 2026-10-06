package com.econet.leads.dto;

/**
 * GET /api/tenders/summary.
 * open: closingAt >= now and status not WON/LOST/IGNORED (awarded SEAO contracts, whose closingAt is
 * the contract end date, are not counted); closingThisWeek: open and closingAt within the next 7
 * days; bidding / submitted / won: count by status.
 */
public record TenderSummaryDTO(long open, long closingThisWeek, long bidding, long submitted, long won) {
}
