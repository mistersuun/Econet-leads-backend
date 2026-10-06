package com.econet.leads.dto;

import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.LeadStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response shapes of the /api/dashboard endpoints.
 */
public final class DashboardDTOs {

    private DashboardDTOs() {
    }

    public record Summary(long totalLeads,
                          long callableLeads,
                          long newLeads,
                          long calls,
                          long callsToday,
                          long conversations,
                          long quotesSent,
                          long won,
                          double conversionRate,
                          long followUpsDue,
                          long followUpsOverdue,
                          BigDecimal pipelineValue,
                          /** leads without a phone whose status is not WON/LOST/DO_NOT_CALL ("À enrichir") */
                          long toEnrich) {
    }

    public record PipelineStage(LeadStatus status, long count) {
    }

    public record ActivityDay(LocalDate date, long calls, long conversations, long won) {
    }

    public record BreakdownRow(String label, long total, long contacted, long won) {
    }

    public record OutcomeCount(CallOutcome outcome, long count) {
    }

    public record LeaderboardRow(UUID userId, String username, long calls, long conversations, long won) {
    }
}
