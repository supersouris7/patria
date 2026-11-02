package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValuationTest {
    private static Property property(String purchase, String low, String high, String fees) {
        return new Property(1, "Bien", "Maison", "", "", "", BigDecimal.ZERO, new BigDecimal(purchase), LocalDate.of(2020, 1, 1),
                new BigDecimal(low), new BigDecimal(high), LocalDate.of(2024, 1, 1), new BigDecimal(fees), BigDecimal.ZERO);
    }

    @Test
    void scenariosAreRoundedToTenThousand() {
        Valuation.Scenarios scenarios = Valuation.of(List.of(property("250000", "302000", "398000", "0.08")));

        assertEquals(new BigDecimal("290000"), scenarios.pessimistic());
        assertEquals(new BigDecimal("300000"), scenarios.prudent());
        assertEquals(new BigDecimal("350000"), scenarios.middle());
        assertEquals(new BigDecimal("400000"), scenarios.optimistic());
    }

    @Test
    void purchaseIsUsedWhenThereIsNoEstimate() {
        assertEquals(new BigDecimal("250000"), Valuation.of(List.of(property("250000", "0", "0", "0.08"))).optimistic());
    }

    @Test
    void saleFeesAreWeightedByValue() {
        BigDecimal rate = Valuation.saleFeesRate(List.of(property("0", "300000", "0", "0.10"), property("0", "100000", "0", "0.02")));

        assertEquals(0, new BigDecimal("0.08").compareTo(rate));
    }

    @Test
    void netValueRemovesSaleFeesAndDebtOnly() {
        assertEquals(0, new BigDecimal("176000").compareTo(Valuation.net(new BigDecimal("300000"), new BigDecimal("0.08"), new BigDecimal("100000"))));
    }
}
