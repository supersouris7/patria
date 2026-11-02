package fr.patria;

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

    private static ScheduleLine line(String date, String type, String amount, String principal, String remaining, String rate) {
        return new ScheduleLine(LocalDate.parse(date), type, decimal(amount), decimal(principal), null, null, decimal(remaining), decimal(rate));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Loan loan(long id, String name, List<ScheduleLine> lines) {
        return LoanMath.derive(id, 1, name, lines);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "attendu " + expected + ", obtenu " + actual);
    }

    @Test
    void loanStartingInTheFutureOwesTheWholeCapital() {
        List<ScheduleLine> lines = List.of(
                line("2027-01-05", "DEBLOCAGE", "200000", null, "200000", "3.5"),
                line("2027-02-05", "ECHEANCE", "1000", "400", "199600", "3.5"),
                line("2047-01-05", "ECHEANCE", "500", "500", "0", "4.0"));

        LoanMath.Status status = LoanMath.status(loan(1, "Futur", lines), lines, TODAY);

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
                line("2026-05-05", "ECHEANCE", "850", "500", "14500", "3.2"),
                line("2026-06-05", "ECHEANCE", "850", "501", "13999", "2.9"),
                line("2026-07-05", "ECHEANCE", "850", "502", "13497", "2.9"),
                line("2026-12-05", "ECHEANCE", "300", "300", "0", "5.0"));

        LoanMath.Status status = LoanMath.status(loan(1, "En cours", lines), lines, TODAY);

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
                line("2025-01-01", "DEBLOCAGE", "20000", null, null, null),
                line("2026-06-01", "REMBOURSEMENT_ANTICIPE", "2000", null, null, null));

        LoanMath.Status status = LoanMath.status(loan(1, "Anticipé", lines), lines, TODAY);

        assertAmount("18000", status.remaining());
        assertAmount("2000", status.repaid());
        assertEquals(10, status.progressPercent());
        assertNull(status.nextInstallment());
    }

    @Test
    void installmentDueTodayIsPaid() {
        ScheduleLine today = line("2026-06-15", "ECHEANCE", "100", "50", "950", null);
        assertTrue(LoanMath.isPast(today, TODAY));
        assertFalse(LoanMath.isPast(line("2026-06-16", "ECHEANCE", "100", "50", "950", null), TODAY));
    }

    @Test
    void monthsAreGroupedAcrossLoansAndPaidOnlyWhenAllInstallmentsPassed() {
        List<ScheduleLine> first = List.of(line("2026-06-05", "ECHEANCE", "800", "500", "9500", null), line("2026-07-05", "ECHEANCE", "800", "500", "9000", null));
        List<ScheduleLine> second = List.of(line("2026-06-20", "ECHEANCE", "200", "150", "4850", null));
        Loan a = loan(1, "A", first);
        Loan b = loan(2, "B", second);

        List<LoanMath.Month> months = LoanMath.months(List.of(a, b), Map.of(1L, first, 2L, second), TODAY);

        assertEquals(2, months.size());
        assertAmount("1000", months.getFirst().total());
        assertFalse(months.getFirst().paid(), "le prêt B n'est pas encore prélevé en juin");
        LoanMath.Month next = LoanMath.nextMonth(List.of(a, b), Map.of(1L, first, 2L, second), TODAY);
        assertEquals(YearMonth.of(2026, 6), next.month());
    }

    @Test
    void deriveTakesFirstValuesOfTheSchedule() {
        List<ScheduleLine> lines = List.of(
                line("2026-01-05", "ECHEANCE", "850", "500", "14500", "3.2"),
                line("2026-12-05", "ECHEANCE", "300", "300", "0", "4.0"));

        Loan loan = LoanMath.derive(7, 3, "Prêt", lines);

        assertAmount("15000", loan.initialAmount());
        assertAmount("3.2", loan.annualRate());
        assertAmount("850", loan.monthlyPayment());
        assertEquals(12, loan.durationMonths());
        assertEquals(5, loan.dueDay());
    }
}
