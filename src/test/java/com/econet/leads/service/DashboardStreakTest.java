package com.econet.leads.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardStreakTest {

    // 2026-11-05 is a Thursday
    static final LocalDate THU = LocalDate.of(2026, 11, 5);

    @Test
    void streakCountsConsecutiveBusinessDays() {
        assertThat(DashboardService.streakDays(Set.of(), THU)).isZero();
        assertThat(DashboardService.streakDays(Set.of(THU), THU)).isEqualTo(1);
        // Tue, Wed, Thu
        assertThat(DashboardService.streakDays(Set.of(THU, THU.minusDays(1), THU.minusDays(2)), THU)).isEqualTo(3);
        // a gap on Wednesday breaks it
        assertThat(DashboardService.streakDays(Set.of(THU, THU.minusDays(2)), THU)).isEqualTo(1);
    }

    @Test
    void streakMayEndOnThePreviousBusinessDay() {
        // no call today yet, calls Mon-Wed -> 3
        assertThat(DashboardService.streakDays(Set.of(THU.minusDays(1), THU.minusDays(2), THU.minusDays(3)), THU)).isEqualTo(3);
        // last call on Tuesday only -> 0 (two days without calls)
        assertThat(DashboardService.streakDays(Set.of(THU.minusDays(2)), THU)).isZero();
    }

    @Test
    void weekendsAreSkipped() {
        LocalDate mon = LocalDate.of(2026, 11, 9);
        LocalDate fri = LocalDate.of(2026, 11, 6);
        LocalDate thu = LocalDate.of(2026, 11, 5);
        // Monday with calls Monday, Friday, Thursday: the weekend does not break the streak
        assertThat(DashboardService.streakDays(Set.of(mon, fri, thu), mon)).isEqualTo(3);
        // on Sunday, the streak ends on Friday
        assertThat(DashboardService.streakDays(Set.of(fri, thu), LocalDate.of(2026, 11, 8))).isEqualTo(2);
    }
}
