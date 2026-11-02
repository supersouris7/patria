package fr.patria.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.TestData;
import fr.patria.common.Frequency;
import fr.patria.expense.Expense;
import fr.patria.expense.ExpenseKind;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.loan.LineType;
import fr.patria.loan.ScheduleLine;
import fr.patria.rent.Occupancy;
import fr.patria.rent.RentPeriod;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Horloge de test au 15/06/2026 : les 12 mois vont de juin 2026 a mai 2027. */
class ForecastTest {
    @TempDir
    Path directory;

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "attendu " + expected + ", obtenu " + actual);
    }

    private Forecast.View forecast(TestData.Stores stores) {
        return new Forecast(new Forecast.Sources(stores.household(), stores.budget(), stores.properties(), stores.loans(), stores.expenses(), stores.rents()),
                TestData.CLOCK).next12Months();
    }

    @Test
    void combinesHouseholdBudgetAndRealEstateMonthByMonth() {
        TestData.Stores stores = TestData.stores(directory);
        long me = stores.household().addPerson("Camille");
        stores.household().addIncome(new Income(0, me, "Salaire", IncomeKind.SALARY, new BigDecimal("3000"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        stores.household().addIncome(new Income(0, me, "13e mois", IncomeKind.BONUS, new BigDecimal("2400"), Frequency.ANNUAL, LocalDate.of(2025, 12, 1), null));
        stores.budget().insert(new BudgetItem(0, "Électricité", Category.ENERGY, new BigDecimal("90"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        stores.budget().insert(new BudgetItem(0, "Assurance auto", Category.INSURANCE, new BigDecimal("600"), Frequency.ANNUAL, LocalDate.of(2026, 7, 1), null));

        long property = stores.properties().insert(TestData.property("Studio", "100000", null, null));
        List<ScheduleLine> schedule = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            schedule.add(new ScheduleLine(LocalDate.of(2026, 1, 5).plusMonths(i), LineType.INSTALLMENT, new BigDecimal("700"), new BigDecimal("500"),
                    null, null, null, null));
        }
        stores.loans().create(property, "Prêt", schedule);
        stores.expenses().save(new Expense(0, property, ExpenseKind.PROPERTY_TAX, 2026, new BigDecimal("900")));
        stores.rents().insert(new RentPeriod(0, property, Occupancy.RENTAL, "", LocalDate.of(2026, 1, 1), null, new BigDecimal("500")));

        Forecast.View view = forecast(stores);
        Forecast.Month june = view.current();
        assertEquals(YearMonth.of(2026, 6), june.month());
        assertAmount("3500", june.income());                       // salaire + loyer
        assertAmount("790", june.spending());                      // électricité + mensualité
        assertAmount("2710", june.balance());

        Forecast.Month july = view.months().get(1);
        assertAmount("1390", july.spending());                     // + assurance auto annuelle
        Forecast.Month october = view.months().get(4);
        assertTrue(october.expenses().stream().anyMatch(line -> line.label().startsWith("Taxe foncière") && line.amount().compareTo(new BigDecimal("900")) == 0));
        Forecast.Month december = view.months().get(6);
        assertAmount("5900", december.income());                   // + 13e mois

        // épargne de précaution : (600 + 900) / 12
        assertAmount("125", view.provision());
        // endettement : 700 / (3 000 + 2 400 / 12 + 500 × 70 %) = 700 / 3 550
        assertAmount("19.7", view.debt().percent());
        assertTrue(view.alerts().stream().anyMatch(alert -> alert.text().startsWith("Juillet 2026 : Assurance auto")));
        stores.database().close();
    }

    @Test
    void deficitAndHighDebtAreFlagged() {
        TestData.Stores stores = TestData.stores(directory);
        long me = stores.household().addPerson("Camille");
        stores.household().addIncome(new Income(0, me, "Salaire", IncomeKind.SALARY, new BigDecimal("1500"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        stores.budget().insert(new BudgetItem(0, "Crédit auto", Category.CREDIT, new BigDecimal("600"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        stores.budget().insert(new BudgetItem(0, "Loyer", Category.HOUSING, new BigDecimal("1000"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));

        Forecast.View view = forecast(stores);

        assertTrue(view.debt().isAboveLimit(), "600 / 1 500 = 40 %");
        assertTrue(view.alerts().stream().anyMatch(alert -> alert.serious() && alert.text().contains("il manque 100")));
        stores.database().close();
    }

    @Test
    void chartStepsAreReadable() {
        assertEquals(1000, BudgetChart.niceStep(830));
        assertEquals(2500, BudgetChart.niceStep(2100));
        assertEquals(5000, BudgetChart.niceStep(4100));
    }
}
