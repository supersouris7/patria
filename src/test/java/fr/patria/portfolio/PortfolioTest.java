package fr.patria.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
