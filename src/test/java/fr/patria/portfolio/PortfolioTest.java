// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.TestData;
import fr.patria.contribution.Contribution;
import fr.patria.loan.LineType;
import fr.patria.loan.ScheduleLine;
import fr.patria.market.MarketService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortfolioTest {
    @TempDir
    Path directory;

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "attendu " + expected + ", obtenu " + actual);
    }

    @Test
    void propertyTaxesAreTotalledPerYearWithEvolution() {
        java.time.LocalDate today = java.time.LocalDate.of(2025, 6, 1);
        fr.patria.property.Property first = TestData.property("A", "100000", null, null).withId(1);
        fr.patria.property.Property second = new fr.patria.property.Property(2, "B", fr.patria.property.PropertyKind.APARTMENT, "", "", null,
                new BigDecimal("100000"), java.time.LocalDate.of(2024, 3, 1), null, null, java.time.LocalDate.of(2024, 3, 1), new BigDecimal("0.08"), null);
        java.util.Map<Long, List<fr.patria.expense.Expense>> expenses = java.util.Map.of(
                1L, List.of(new fr.patria.expense.Expense(0, 1, fr.patria.expense.ExpenseKind.PROPERTY_TAX, 2024, new BigDecimal("1000"))),
                2L, List.of(new fr.patria.expense.Expense(0, 2, fr.patria.expense.ExpenseKind.PROPERTY_TAX, 2024, new BigDecimal("500"))));

        List<Portfolio.TaxYear> years = Portfolio.propertyTaxes(List.of(first, second), expenses, today);

        assertEquals(2025, years.getFirst().year(), "année la plus récente en premier");
        assertAmount("1526", years.getFirst().amount());        // 1 000 × 1,017 + 500 × 1,017, arrondis par bien : 1 017 + 509
        assertAmount("1.7", years.getFirst().evolution());
        assertEquals(0, years.getFirst().entered());
        Portfolio.TaxYear year2024 = years.get(1);
        assertEquals(2, year2024.entered());
        assertTrue(year2024.newProperty(), "le bien B apparaît en 2024");
        assertEquals(2020, years.getLast().year(), "depuis l'achat du premier bien");
    }

    @Test
    void overviewAddsRetainedValuesAndComputesNetPerProperty() {
        TestData.Stores stores = TestData.stores(directory);
        long house = stores.properties().insert(TestData.property("Maison", "250000", "300000", "400000"));
        stores.properties().updateSelectedValue(house, new BigDecimal("350000"));
        long studio = stores.properties().insert(TestData.property("Studio", "100000", null, null));
        stores.loans().create(house, "Prêt", List.of(
                new ScheduleLine(LocalDate.of(2026, 1, 1), LineType.DRAWDOWN, new BigDecimal("200000"), null, null, null, new BigDecimal("200000"), new BigDecimal("3")),
                new ScheduleLine(LocalDate.of(2026, 6, 1), LineType.INSTALLMENT, new BigDecimal("1000"), new BigDecimal("500"), null, null, new BigDecimal("199500"), new BigDecimal("3")),
                new ScheduleLine(LocalDate.of(2026, 7, 1), LineType.INSTALLMENT, new BigDecimal("1000"), new BigDecimal("501"), null, null, new BigDecimal("198999"), new BigDecimal("3"))));
        stores.contributions().insert(new Contribution(0, studio, "Épargne", new BigDecimal("20000"), LocalDate.of(2021, 1, 1)));

        Portfolio.Overview overview = new Portfolio(new Portfolio.Stores(stores.properties(), stores.loans(), stores.contributions(), stores.expenses(), stores.rents()),
                new MarketService(stores.database(), TestData.CLOCK), TestData.CLOCK).overview();

        assertAmount("450000", overview.value());
        assertAmount("199500", overview.debt());
        // (350 000 × 0,92 − 199 500) + 100 000 × 0,92
        assertAmount("214500", overview.net());
        assertAmount("100000", overview.gain());
        assertAmount("20000", overview.contributed());
        assertAmount("1000", overview.nextMonth().total());
        Portfolio.PropertySummary summary = overview.properties().stream().filter(s -> s.property().id() == house).findFirst().orElseThrow();
        assertEquals("Fourchette", summary.scenario());
        assertAmount("3665", summary.squareMeter().value()); // 350 000 / 95,5 m²
        // coût du crédit : échéance de juin (1 000 − 500) ; enrichissement = 350 000 × 0,92 − 250 000 − 500
        assertAmount("500", summary.returns().creditCost());
        assertAmount("71500", summary.returns().realGain());
        stores.database().close();
    }
}
