// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.TestData;
import fr.patria.common.Frequency;
import fr.patria.expense.Expense;
import fr.patria.expense.ExpenseKind;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.household.Shares;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Horloge de test au 15/06/2026 : les 12 mois vont de juin 2026 a mai 2027. */
class ForecastTest {
    @TempDir
    Path directory;

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "attendu " + expected + ", obtenu " + actual);
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> message + " : attendu " + expected + ", obtenu " + actual);
    }

    private Forecast.View forecast(TestData.Stores stores) {
        return forecast(stores, null);
    }

    private Forecast.View forecast(TestData.Stores stores, Long person) {
        return new Forecast(new Forecast.Sources(stores.household(), stores.budget(), stores.properties(), stores.loans(), stores.expenses(), stores.rents(), stores.shares()),
                TestData.CLOCK).next12Months(person);
    }

    @Test
    void transfersToJointAccountAreSpendingForSenderIncomeForAccountAndNeutralForHousehold() {
        TestData.Stores stores = TestData.stores(directory);
        long alice = stores.household().addPerson("Alice");
        long bob = stores.household().addPerson("Bob");
        long joint = stores.household().addMember("Compte joint", fr.patria.household.Person.Kind.ACCOUNT);
        stores.household().addIncome(new Income(0, alice, "Salaire", IncomeKind.SALARY, new BigDecimal("3000"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        stores.household().addIncome(new Income(0, bob, "Salaire", IncomeKind.SALARY, new BigDecimal("2000"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        long food = stores.budget().insert(new BudgetItem(0, "Courses", Category.OTHER, new BigDecimal("600"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        long car = stores.budget().insert(new BudgetItem(0, "Crédit auto", Category.CREDIT, new BigDecimal("300"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        stores.shares().set(Shares.Subject.BUDGET_ITEM, food, Map.of(joint, 100));
        stores.shares().set(Shares.Subject.BUDGET_ITEM, car, Map.of(joint, 100));
        LocalDate today = LocalDate.now(TestData.CLOCK);
        stores.household().addTransfer(new fr.patria.household.Transfer(0, alice, joint, "", new BigDecimal("1000"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        stores.household().addTransfer(new fr.patria.household.Transfer(0, bob, joint, "", new BigDecimal("800"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        stores.household().addTransfer(new fr.patria.household.Transfer(0, bob, joint, "Vacances", new BigDecimal("500"), Frequency.ONCE, today, null));

        Forecast.Month household = forecast(stores).current();
        assertAmount("5000", household.income());
        assertAmount("900", household.spending());

        Forecast.View aliceView = forecast(stores, alice);
        assertAmount("3000", aliceView.current().income());
        assertAmount("1000", aliceView.current().spending());
        assertTrue(aliceView.current().expenses().stream().anyMatch(line -> line.label().equals("Virement vers Compte joint") && line.category() == Category.TRANSFER));
        // credit paye par le compte joint : moitie chacun ; le virement ne compte pas
        assertAmount("150", aliceView.debt().credits());
        assertAmount("3000", aliceView.debt().income());

        Forecast.View bobView = forecast(stores, bob);
        assertAmount("1300", bobView.current().spending());
        assertTrue(bobView.current().expenses().stream().anyMatch(line -> line.label().equals("Vacances (vers Compte joint)")));
        assertAmount("800", bobView.months().get(1).spending(), "ponctuel : seulement ce mois-ci");

        Forecast.View jointView = forecast(stores, joint);
        assertAmount("2300", jointView.current().income());
        assertAmount("900", jointView.current().spending());
        assertNull(jointView.debt().percent(), "pas de taux pour un compte");
        // les budgets des membres redonnent celui du foyer
        assertAmount(household.balance().toPlainString(), aliceView.current().balance().add(bobView.current().balance()).add(jointView.current().balance()));
        stores.database().close();
    }

    @Test
    void eachPersonSeesOwnIncomesAndShareOfExpensesLoansAndRents() {
        TestData.Stores stores = TestData.stores(directory);
        long alice = stores.household().addPerson("Alice");
        long bob = stores.household().addPerson("Bob");
        stores.household().addIncome(new Income(0, alice, "Salaire", IncomeKind.SALARY, new BigDecimal("3000"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        stores.household().addIncome(new Income(0, bob, "Salaire", IncomeKind.SALARY, new BigDecimal("2000"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        long electricity = stores.budget().insert(new BudgetItem(0, "Électricité", Category.ENERGY, new BigDecimal("100"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        long car = stores.budget().insert(new BudgetItem(0, "Crédit auto", Category.CREDIT, new BigDecimal("300"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));
        stores.shares().set(Shares.Subject.BUDGET_ITEM, car, Map.of(bob, 100));          // à Bob seul ; électricité à parts égales

        long property = stores.properties().insert(TestData.property("Studio", "100000", null, null));
        List<ScheduleLine> schedule = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            schedule.add(new ScheduleLine(LocalDate.of(2026, 1, 5).plusMonths(i), LineType.INSTALLMENT, new BigDecimal("800"), null, null, null, null, null));
        }
        stores.loans().create(property, "Prêt", schedule);                                 // suit la répartition du bien
        stores.shares().set(Shares.Subject.PROPERTY, property, Map.of(alice, 75, bob, 25));
        stores.rents().insert(new RentPeriod(0, property, Occupancy.RENTAL, "", LocalDate.of(2026, 1, 1), null, new BigDecimal("400")));

        Forecast.Month household = forecast(stores).current();
        assertAmount("5400", household.income());
        assertAmount("1200", household.spending());

        Forecast.View aliceView = forecast(stores, alice);
        assertEquals("Alice", aliceView.person().name());
        assertAmount("3300", aliceView.current().income());                // 3 000 + 75 % du loyer
        assertAmount("650", aliceView.current().spending());               // 50 électricité + 600 de prêt
        assertTrue(aliceView.current().expenses().stream().anyMatch(line -> line.label().equals("Électricité (50\u00a0%)")));
        // endettement : 600 / (3 000 + 300 × 70 %)
        assertAmount("18.7", aliceView.debt().percent());

        Forecast.Month bobMonth = forecast(stores, bob).current();
        assertAmount("2100", bobMonth.income());
        assertAmount("550", bobMonth.spending());                          // 50 + 300 crédit auto + 200 de prêt

        // les parts des deux personnes refont le total du foyer
        assertAmount(household.spending().toPlainString(), aliceView.current().spending().add(bobMonth.spending()));
        stores.database().close();
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
    void propertyTaxCanBePaidByAJointAccount() {
        TestData.Stores stores = TestData.stores(directory);
        long alice = stores.household().addPerson("Alice");
        long bob = stores.household().addPerson("Bob");
        long joint = stores.household().addMember("Compte joint", fr.patria.household.Person.Kind.ACCOUNT);
        long property = stores.properties().insert(TestData.property("Maison", "200000", null, null));
        stores.expenses().save(new Expense(0, property, ExpenseKind.PROPERTY_TAX, 2026, new BigDecimal("1760")));
        stores.expenses().save(new Expense(0, property, ExpenseKind.INSURANCE, 2026, new BigDecimal("1200")));
        stores.expenses().setPropertyTaxMonthly(property, true);
        java.util.function.Function<Forecast.Month, BigDecimal> tax = month -> month.expenses().stream()
                .filter(line -> line.label().startsWith("Taxe foncière")).map(Forecast.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertAmount("88", tax.apply(forecast(stores, alice).current()));        // comme le bien : parts égales entre personnes
        assertAmount("0", tax.apply(forecast(stores, joint).current()));

        stores.shares().setPropertyTaxPayer(property, joint);
        assertAmount("0", tax.apply(forecast(stores, alice).current()));
        assertAmount("0", tax.apply(forecast(stores, bob).current()));
        Forecast.Month account = forecast(stores, joint).current();
        assertAmount("176", tax.apply(account));                                 // toute sur le compte joint
        assertAmount("176", account.spending());                                 // l'assurance reste comme le bien
        Forecast.Month household = forecast(stores).current();
        assertAmount("176", tax.apply(household));
        assertTrue(household.expenses().stream().anyMatch(line -> line.label().equals("Taxe foncière · Maison · Compte joint")));
        assertAmount("50", forecast(stores, alice).current().spending());       // assurance 100 / 2

        stores.shares().setPropertyTaxPayer(property, null);
        assertAmount("88", tax.apply(forecast(stores, alice).current()));
        stores.database().close();
    }

    @Test
    void monthlyPropertyTaxIsSpreadFromJanuaryToOctober() {
        TestData.Stores stores = TestData.stores(directory);
        long property = stores.properties().insert(TestData.property("Maison", "200000", null, null));
        stores.expenses().save(new Expense(0, property, ExpenseKind.PROPERTY_TAX, 2026, new BigDecimal("1760")));
        stores.expenses().save(new Expense(0, property, ExpenseKind.PROPERTY_TAX, 2027, new BigDecimal("1801")));
        stores.expenses().setPropertyTaxMonthly(property, true);

        Forecast.View view = forecast(stores);   // juin 2026 → mai 2027
        java.util.function.Function<Forecast.Month, BigDecimal> tax = month -> month.expenses().stream()
                .filter(line -> line.label().startsWith("Taxe foncière")).map(Forecast.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertAmount("176", tax.apply(view.months().get(0)));          // juin 2026
        assertAmount("176", tax.apply(view.months().get(4)));          // octobre 2026
        assertAmount("0", tax.apply(view.months().get(5)));            // novembre : rien
        assertAmount("180.10", tax.apply(view.months().get(7)));       // janvier 2027 : 1 801 / 10
        assertAmount("0", view.provision());                           // mensualisée : rien à mettre de côté
        BigDecimal year2027 = BigDecimal.ZERO;
        // Fonction utilisee par le calcul du mois (Forecast.month)
        for (int m = 1; m <= 10; m++) year2027 = year2027.add(Forecast.propertyTax(TestData.property("Maison", "200000", null, null),
                stores.expenses().forProperty(property), true, YearMonth.of(2027, m)).amount());
        assertAmount("1801", year2027);                                // octobre absorbe l'arrondi

        stores.expenses().setPropertyTaxMonthly(property, false);
        view = forecast(stores);
        assertAmount("0", tax.apply(view.months().get(0)));
        assertAmount("1760", tax.apply(view.months().get(4)));         // en une fois, en octobre
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
    void manyDeficitMonthsAreGroupedInOneAlert() {
        TestData.Stores stores = TestData.stores(directory);
        long me = stores.household().addPerson("Camille");
        stores.household().addIncome(new Income(0, me, "Salaire", IncomeKind.SALARY, new BigDecimal("1000"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), null));
        stores.budget().insert(new BudgetItem(0, "Loyer", Category.HOUSING, new BigDecimal("1200"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null));

        List<Forecast.Alert> alerts = forecast(stores).alerts();

        assertEquals(1, alerts.stream().filter(alert -> alert.text().contains("déficit")).count());
        assertTrue(alerts.stream().anyMatch(alert -> alert.text().equals("12 mois sur 12 en déficit, dès juin 2026 : il manque 200\u00a0€ par mois.")),
                alerts.toString());
        stores.database().close();
    }

    @Test
    void chartStepsAreReadable() {
        assertEquals(1000, BudgetChart.niceStep(830));
        assertEquals(2500, BudgetChart.niceStep(2100));
        assertEquals(5000, BudgetChart.niceStep(4100));
    }

    @Test
    void debtLevelsFollowThe33And35PercentThresholds() {
        BigDecimal income = new BigDecimal("3000");
        assertEquals(Forecast.DebtRatio.Level.GOOD, new Forecast.DebtRatio(new BigDecimal("960"), income, new BigDecimal("32.9")).level());
        assertEquals(Forecast.DebtRatio.Level.WARNING, new Forecast.DebtRatio(new BigDecimal("990"), income, new BigDecimal("33.0")).level());
        assertEquals(Forecast.DebtRatio.Level.WARNING, new Forecast.DebtRatio(new BigDecimal("1050"), income, new BigDecimal("35.0")).level());
        assertEquals(Forecast.DebtRatio.Level.SERIOUS, new Forecast.DebtRatio(new BigDecimal("1053"), income, new BigDecimal("35.1")).level());
        // 33 % de 3 000 = 990 : il reste 90 € de mensualite possible
        assertEquals(0, new BigDecimal("90").compareTo(new Forecast.DebtRatio(new BigDecimal("900"), income, new BigDecimal("30.0")).marginToComfort()));
        assertEquals(0, BigDecimal.ZERO.compareTo(new Forecast.DebtRatio(new BigDecimal("1053"), income, new BigDecimal("35.1")).marginToComfort()));
    }
}
