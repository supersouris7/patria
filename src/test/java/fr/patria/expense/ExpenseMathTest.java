package fr.patria.expense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseMathTest {
    private static final LocalDate PURCHASE = LocalDate.of(2022, 7, 1);
    private static final LocalDate TODAY = LocalDate.of(2025, 12, 31);

    private static Expense tax(int year, String amount) {
        return new Expense(0, 1, ExpenseKind.PROPERTY_TAX, year, new BigDecimal(amount));
    }

    private static ExpenseMath.YearAmount year(List<ExpenseMath.YearAmount> years, int year) {
        return years.stream().filter(y -> y.year() == year).findFirst().orElseThrow();
    }

    @Test
    void oneNoticeIsEnoughPropertyTaxIsRevaluedBothWays() {
        List<ExpenseMath.YearAmount> years = ExpenseMath.years(List.of(tax(2024, "1000")), PURCHASE, TODAY);

        assertEquals(4, years.size(), "2022 à 2025");
        assertFalse(year(years, 2024).estimated());
        assertEquals(new BigDecimal("1017"), year(years, 2025).amount(), "+1,7 % en 2025");
        assertEquals(new BigDecimal("962"), year(years, 2023).amount(), "1000 / 1,039");
        assertEquals(new BigDecimal("899"), year(years, 2022).amount(), "1000 / (1,039 × 1,071)");
        assertTrue(year(years, 2022).estimated());
    }

    @Test
    void otherChargesStayConstantAndEnteredYearsWin() {
        List<Expense> entered = List.of(new Expense(0, 1, ExpenseKind.CONDO_FEES, 2023, new BigDecimal("1200")),
                new Expense(0, 1, ExpenseKind.CONDO_FEES, 2025, new BigDecimal("1500")));
        List<ExpenseMath.YearAmount> years = ExpenseMath.years(entered, PURCHASE, TODAY);

        assertEquals(new BigDecimal("1200"), year(years, 2022).amount());
        assertEquals(new BigDecimal("1200"), year(years, 2024).amount(), "à égale distance : l'année antérieure");
        assertEquals(new BigDecimal("1500"), year(years, 2025).amount());
    }

    @Test
    void paidToDateIsProratedOnPurchaseYear() {
        // 2022 : 184 jours sur 365 de 1 200 €, puis 2023, 2024 et 2025 complets
        BigDecimal paid = ExpenseMath.paidToDate(List.of(new Expense(0, 1, ExpenseKind.INSURANCE, 2023, new BigDecimal("1200"))), PURCHASE, TODAY);
        assertEquals(new BigDecimal("4204.93"), paid);
    }
}
