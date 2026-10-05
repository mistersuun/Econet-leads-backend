package com.econet.leads.service;

import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.LeadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LeadServiceRulesTest {

    // ---------------------------------------------------------------- status transitions

    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource({
            // no conversation: CONTACTED unless already further in the pipeline
            "NO_ANSWER, NEW, CONTACTED",
            "NO_ANSWER, CONTACTED, CONTACTED",
            "NO_ANSWER, INTERESTED, INTERESTED",
            "NO_ANSWER, QUOTE_SENT, QUOTE_SENT",
            "NO_ANSWER, WON, WON",
            "NO_ANSWER, LOST, CONTACTED",
            "VOICEMAIL, NEW, CONTACTED",
            "VOICEMAIL, INTERESTED, INTERESTED",
            "VOICEMAIL, QUOTE_SENT, QUOTE_SENT",
            "CALLBACK, NEW, CONTACTED",
            "CALLBACK, INTERESTED, INTERESTED",
            "CALLBACK, QUOTE_SENT, QUOTE_SENT",
            "CALLBACK, DO_NOT_CALL, CONTACTED",
            // explicit outcomes
            "INTERESTED, NEW, INTERESTED",
            "INTERESTED, QUOTE_SENT, INTERESTED",
            "QUOTE_SENT, CONTACTED, QUOTE_SENT",
            "WON, QUOTE_SENT, WON",
            "WON, NEW, WON",
            "NOT_INTERESTED, INTERESTED, LOST",
            "NOT_INTERESTED, NEW, LOST",
            "WRONG_NUMBER, NEW, DO_NOT_CALL",
            "WRONG_NUMBER, CONTACTED, DO_NOT_CALL",
            "DO_NOT_CALL, INTERESTED, DO_NOT_CALL",
    })
    void nextStatus(CallOutcome outcome, LeadStatus current, LeadStatus expected) {
        assertThat(LeadService.nextStatus(current, outcome)).isEqualTo(expected);
    }

    @Test
    void everyOutcomeHasATransitionFromEveryStatus() {
        for (CallOutcome outcome : CallOutcome.values()) {
            for (LeadStatus status : LeadStatus.values()) {
                assertThat(LeadService.nextStatus(status, outcome)).isNotNull();
            }
        }
    }

    // ---------------------------------------------------------------- follow-ups

    // 2026-10-05 is a Monday
    private static final LocalDateTime MONDAY_2PM = LocalDateTime.of(2026, 10, 5, 14, 30);
    private static final LocalDateTime THURSDAY = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final LocalDateTime FRIDAY = LocalDateTime.of(2026, 10, 9, 16, 0);
    private static final LocalDateTime SATURDAY = LocalDateTime.of(2026, 10, 10, 11, 0);
    private static final LocalDateTime WEDNESDAY = LocalDateTime.of(2026, 10, 7, 11, 0);

    @ParameterizedTest
    @CsvSource({"NO_ANSWER", "VOICEMAIL", "CALLBACK"})
    void noConversationOutcomesFollowUpInTwoBusinessDaysAt10(CallOutcome outcome) {
        assertThat(LeadService.defaultFollowUp(outcome, LeadStatus.CONTACTED, MONDAY_2PM))
                .isEqualTo(LocalDateTime.of(2026, 10, 7, 10, 0)); // Wednesday
        assertThat(LeadService.defaultFollowUp(outcome, LeadStatus.CONTACTED, THURSDAY))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0)); // Monday
        assertThat(LeadService.defaultFollowUp(outcome, LeadStatus.CONTACTED, FRIDAY))
                .isEqualTo(LocalDateTime.of(2026, 10, 13, 10, 0)); // Tuesday
        assertThat(LeadService.defaultFollowUp(outcome, LeadStatus.CONTACTED, SATURDAY))
                .isEqualTo(LocalDateTime.of(2026, 10, 13, 10, 0)); // Tuesday
    }

    @ParameterizedTest
    @CsvSource({"INTERESTED, INTERESTED", "QUOTE_SENT, QUOTE_SENT"})
    void interestedAndQuoteFollowUpInThreeDaysAt10(CallOutcome outcome, LeadStatus status) {
        assertThat(LeadService.defaultFollowUp(outcome, status, MONDAY_2PM))
                .isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 0)); // Thursday
        // Wednesday + 3 = Saturday -> moved to Monday
        assertThat(LeadService.defaultFollowUp(outcome, status, WEDNESDAY))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0));
    }

    @ParameterizedTest
    @CsvSource({"WON, WON", "NOT_INTERESTED, LOST", "WRONG_NUMBER, DO_NOT_CALL", "DO_NOT_CALL, DO_NOT_CALL"})
    void terminalOutcomesHaveNoFollowUp(CallOutcome outcome, LeadStatus status) {
        assertThat(LeadService.defaultFollowUp(outcome, status, MONDAY_2PM)).isNull();
    }

    @Test
    void noAnswerOnAWonCustomerSchedulesNothing() {
        LeadStatus status = LeadService.nextStatus(LeadStatus.WON, CallOutcome.NO_ANSWER);
        assertThat(LeadService.defaultFollowUp(CallOutcome.NO_ANSWER, status, MONDAY_2PM)).isNull();
    }

    @Test
    void addBusinessDaysSkipsWeekends() {
        LocalDate friday = LocalDate.of(2026, 10, 9);
        assertThat(LeadService.addBusinessDays(friday, 1)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(LeadService.addBusinessDays(friday, 5)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(LeadService.addBusinessDays(friday, 0)).isEqualTo(friday);
    }
}
