// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class FrequencyTest {
    private static final YearMonth MARCH_2026 = YearMonth.of(2026, 3);

    @Test
    void firstPaymentSetsTheCycle() {
        assertTrue(Frequency.ANNUAL.occursIn(MARCH_2026, null, YearMonth.of(2027, 3)));
        assertFalse(Frequency.ANNUAL.occursIn(MARCH_2026, null, YearMonth.of(2027, 4)));
        assertTrue(Frequency.QUARTERLY.occursIn(MARCH_2026, null, YearMonth.of(2026, 9)));
        assertFalse(Frequency.QUARTERLY.occursIn(MARCH_2026, null, YearMonth.of(2026, 8)));
        assertTrue(Frequency.ONCE.occursIn(MARCH_2026, null, MARCH_2026));
        assertFalse(Frequency.ONCE.occursIn(MARCH_2026, null, YearMonth.of(2027, 3)));
    }

    @Test
    void nothingBeforeStartNorAfterEnd() {
        assertFalse(Frequency.MONTHLY.occursIn(MARCH_2026, YearMonth.of(2026, 6), YearMonth.of(2026, 2)));
        assertFalse(Frequency.MONTHLY.occursIn(MARCH_2026, YearMonth.of(2026, 6), YearMonth.of(2026, 7)));
        assertTrue(Frequency.MONTHLY.occursIn(MARCH_2026, YearMonth.of(2026, 6), YearMonth.of(2026, 6)));
    }

    @Test
    void monthlyEquivalent() {
        assertEquals(new BigDecimal("50.00"), Frequency.ANNUAL.monthly(new BigDecimal("600")));
        assertEquals(new BigDecimal("200.00"), Frequency.QUARTERLY.monthly(new BigDecimal("600")));
        assertEquals(BigDecimal.ZERO, Frequency.ONCE.monthly(new BigDecimal("600")));
    }
}
