package fr.patria.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoanMathTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    static ScheduleLine line(String date, LineType type, String amount, String principal, String remaining, String rate) {
        return new ScheduleLine(LocalDate.parse(date), type, decimal(amount), decimal(principal), null, null, decimal(remaining), decimal(rate));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "attendu " + expected + ", obtenu " + actual);
    }

    @Test
    void loanStartingInTheFutureOwesTheWholeCapital() {
        List<ScheduleLine> lines = List.of(
                line("2027-01-05", LineType.DRAWDOWN, "200000", null, "200000", "3.5"),
                line("2027-02-05", LineType.INSTALLMENT, "1000", "400", "199600", "3.5"),
                line("2047-01-05", LineType.INSTALLMENT, "500", "500", "0", "4.0"));

        LoanMath.Status status = LoanMath.status(lines, TODAY);

        assertAmount("200000", status.borrowed());
        assertAmount("200000", status.remaining());
        assertAmount("0", status.repaid());
        assertAmount("1000", status.nextInstallment().amount());
        assertEquals(LocalDate.of(2027, 2, 5), status.nextInstallment().date());
        assertAmount("3.5", status.currentRate());
        assertEquals(0, status.progressPercent());
    }

    @Test
    void runningLoanUsesLastPastLineAndCurrentRate() {
        List<ScheduleLine> lines = List.of(
                line("2026-05-05", LineType.INSTALLMENT, "850", "500", "14500", "3.2"),
                line("2026-06-05", LineType.INSTALLMENT, "850", "501", "13999", "2.9"),
                line("2026-07-05", LineType.INSTALLMENT, "850", "502", "13497", "2.9"),
                line("2026-12-05", LineType.INSTALLMENT, "300", "300", "0", "5.0"));

        LoanMath.Status status = LoanMath.status(lines, TODAY);

        assertAmount("15000", status.borrowed());
        assertAmount("13999", status.remaining());
        assertAmount("1001", status.repaid());
        assertAmount("2.9", status.currentRate());
        assertEquals(LocalDate.of(2026, 7, 5), status.nextInstallment().date());
        assertEquals(6, status.remainingMonths());
    }

    @Test
    void earlyRepaymentWithoutRemainingColumnReducesBalance() {
        List<ScheduleLine> lines = List.of(
                line("2025-01-01", LineType.DRAWDOWN, "20000", null, null, null),
                line("2026-06-01", LineType.EARLY_REPAYMENT, "2000", null, null, null));

        LoanMath.Status status = LoanMath.status(lines, TODAY);

        assertAmount("18000", status.remaining());
        assertAmount("2000", status.repaid());
        assertEquals(10, status.progressPercent());
        assertNull(status.nextInstallment());
    }

    @Test
    void installmentDueTodayIsPaid() {
        assertTrue(LoanMath.isPast(line("2026-06-15", LineType.INSTALLMENT, "100", "50", "950", null), TODAY));
        assertFalse(LoanMath.isPast(line("2026-06-16", LineType.INSTALLMENT, "100", "50", "950", null), TODAY));
    }

    @Test
    void monthsAreGroupedAcrossLoansAndPaidOnlyWhenAllInstallmentsPassed() {
        List<ScheduleLine> first = List.of(line("2026-06-05", LineType.INSTALLMENT, "800", "500", "9500", null),
                line("2026-07-05", LineType.INSTALLMENT, "800", "500", "9000", null));
        List<ScheduleLine> second = List.of(line("2026-06-20", LineType.INSTALLMENT, "200", "150", "4850", null));
        List<Loan> loans = List.of(new Loan(1, 1, "A"), new Loan(2, 1, "B"));
        Map<Long, List<ScheduleLine>> schedules = Map.of(1L, first, 2L, second);

        List<LoanMath.Month> months = LoanMath.months(loans, schedules, TODAY);

        assertEquals(2, months.size());
        assertAmount("1000", months.getFirst().total());
        assertFalse(months.getFirst().paid(), "le prêt B n'est pas encore prélevé en juin");
        assertEquals(YearMonth.of(2026, 6), LoanMath.nextMonth(loans, schedules, TODAY).month());
    }
}
