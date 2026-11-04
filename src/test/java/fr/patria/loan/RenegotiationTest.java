package fr.patria.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class RenegotiationTest {
    private static LoanMath.Status status(String remaining, String rate, long months) {
        return new LoanMath.Status(new BigDecimal("200000"), BigDecimal.ZERO, new BigDecimal(remaining), new BigDecimal(rate), null,
                LocalDate.of(2046, 1, 1), months, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static Renegotiation.Reference market(String rate) {
        return new Renegotiation.Reference(new BigDecimal(rate), YearMonth.of(2026, 9));
    }

    @Test
    void paymentOfAnAmortizingLoan() {
        assertEquals(new BigDecimal("1109.20"), Renegotiation.payment(new BigDecimal("200000"), new BigDecimal("3"), 240), "200 000 € à 3 % sur 20 ans");
        assertEquals(new BigDecimal("833.33"), Renegotiation.payment(new BigDecimal("200000"), BigDecimal.ZERO, 240), "taux zéro");
    }

    @Test
    void expensiveLoanIsWorthRenegotiatingAfterPenalties() {
        Renegotiation result = Renegotiation.of(status("200000", "4.5", 240), market("3.2")).orElseThrow();
        assertEquals(0, new BigDecimal("1.3").compareTo(result.gap()));
        assertTrue(result.grossSaving().compareTo(new BigDecimal("30000")) > 0, "environ 150 € par mois pendant 20 ans");
        assertEquals(0, new BigDecimal("4500.00").compareTo(result.penalty()), "6 mois d'intérêts (4 500 €) sous le plafond de 3 % (6 000 €)");
        assertEquals(0, result.netSaving().compareTo(result.grossSaving().subtract(result.penalty())));
        assertEquals(Renegotiation.Verdict.WORTH, result.verdict());
    }

    @Test
    void cheapLoanHasNothingToGain() {
        Renegotiation result = Renegotiation.of(status("150000", "1.35", 150), market("3.19")).orElseThrow();
        assertTrue(result.gap().signum() < 0);
        assertTrue(result.grossSaving().signum() < 0, "le marché est plus cher");
        assertEquals(Renegotiation.Verdict.NONE, result.verdict());
    }

    @Test
    void smallGapAndShortRemainingAreFlagged() {
        Renegotiation result = Renegotiation.of(status("200000", "3.8", 110), market("3.2")).orElseThrow();
        assertTrue(result.shortRemaining(), "moins de 10 ans restants");
        assertTrue(result.netSaving().signum() > 0, "environ 2 300 € après pénalités");
        assertEquals(Renegotiation.Verdict.SMALL, result.verdict(), "écart de 0,6 point, sous le repère de 0,7");
        Renegotiation absorbed = Renegotiation.of(status("80000", "3.6", 96), market("3.2")).orElseThrow();
        assertEquals(Renegotiation.Verdict.NONE, absorbed.verdict(), "8 ans restants : les pénalités absorbent l'économie");
    }

    @Test
    void noComparisonForAFinishedLoanOrWithoutReference() {
        assertTrue(Renegotiation.of(status("0", "3", 0), market("3.2")).isEmpty());
        assertTrue(Renegotiation.of(status("100000", "3", 120), null).isEmpty());
    }
}
