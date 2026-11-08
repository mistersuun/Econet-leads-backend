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

    // --- addendum 3 ---

    /** What the queue learned per business type over the last 180 days (rate is Laplace-smoothed). */
    public record ConversionByType(String businessType, long leadsCalled, long conversations, long positive, double rate) {
    }

    public record Funnel(long dials, long connects, long conversations, long interested, long quotes, long won) {
    }

    /** dow 1..7 (ISO, Monday = 1), hour 0..23, Montreal time. */
    public record BestHour(int dow, int hour, long calls, long connects, long positive) {
    }

    public record Rank(int position, int of) {
    }

    public record MeToday(long callsToday,
                          long conversationsToday,
                          long quotesToday,
                          long wonToday,
                          int callTarget,
                          int conversationTarget,
                          int streakDays,
                          long bestDayCalls,
                          Rank rankToday) {
    }

    public record TeamToday(UUID userId, String username, long callsToday, long conversationsToday,
                            String status, String currentLeadName) {
    }

    /** Calls and conversations of one user today (target.progress event). */
    public record TodayCounts(long callsToday, long conversationsToday) {
    }
}
