package com.econet.leads.integration.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ImportValuesTest {

    @Test
    void parsesOpenDataDateFormats() {
        assertThat(ImportValues.dateTime("2026-09-12")).isEqualTo(LocalDateTime.of(2026, 9, 12, 0, 0));
        assertThat(ImportValues.dateTime("2026-07-10T17:00:00")).isEqualTo(LocalDateTime.of(2026, 7, 10, 17, 0));
        assertThat(ImportValues.dateTime("2026-07-10 17:00")).isEqualTo(LocalDateTime.of(2026, 7, 10, 17, 0));
        // offsets are converted to Montreal wall time (EDT in October)
        assertThat(ImportValues.dateTime("2026-10-16T18:00:00Z")).isEqualTo(LocalDateTime.of(2026, 10, 16, 14, 0));
        assertThat(ImportValues.dateTime("2026-10-16T14:00:00-04:00")).isEqualTo(LocalDateTime.of(2026, 10, 16, 14, 0));
        assertThat(ImportValues.dateTime("n/a")).isNull();
    }

    @Test
    void parsesAmountsAndFormatsMoney() {
        assertThat(ImportValues.amount("450 000,50")).isEqualByComparingTo("450000.50");
        assertThat(ImportValues.amount("$450,000")).isEqualByComparingTo("450000");
        assertThat(ImportValues.amount("1250000.0")).isEqualByComparingTo("1250000");
        assertThat(ImportValues.amount("")).isNull();
        assertThat(ImportValues.money(new BigDecimal("450000"))).isEqualTo("450 000 $");
    }
}
