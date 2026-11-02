package fr.patria.rent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RentMathTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 30);

    @Test
    void rentsAreCountedPerPeriodWithVacancyBetween() {
        List<RentPeriod> periods = List.of(
                new RentPeriod(1, 1, "A", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), new BigDecimal("800")),
                new RentPeriod(2, 1, "B", LocalDate.of(2026, 3, 1), null, new BigDecimal("850")));

        // 2025 : 365 j × 9 600 / 365,25 ; 2026 : 122 j × 10 200 / 365,25
        assertEquals(new BigDecimal("13000.41"), RentMath.receivedToDate(periods, TODAY));
        assertEquals(new BigDecimal("850"), RentMath.currentMonthly(periods, TODAY));
        assertEquals(BigDecimal.ZERO, RentMath.currentMonthly(periods, LocalDate.of(2026, 2, 1)), "vacant en février");
    }
}
