package fr.patria.property;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ValuationTest {
    static Property property(String purchase, String low, String high) {
        return new Property(1, "Bien", PropertyKind.HOUSE, "", "", null, new BigDecimal(purchase), LocalDate.of(2020, 1, 1),
                low == null ? null : new BigDecimal(low), high == null ? null : new BigDecimal(high), LocalDate.of(2024, 1, 1),
                new BigDecimal("0.08"), null);
    }

    @Test
    void scenariosAreRoundedToTenThousand() {
        Valuation.Scenarios scenarios = Valuation.scenarios(property("250000", "302000", "398000"));

        assertEquals(new BigDecimal("290000"), scenarios.pessimistic());
        assertEquals(new BigDecimal("300000"), scenarios.prudent());
        assertEquals(new BigDecimal("350000"), scenarios.middle());
        assertEquals(new BigDecimal("400000"), scenarios.optimistic());
    }

    @Test
    void purchaseIsUsedWhenThereIsNoEstimate() {
        assertEquals(new BigDecimal("250000"), Valuation.scenarios(property("250000", null, null)).optimistic());
    }

    @Test
    void retainedValueDefaultsToPrudentThenFollowsSelection() {
        Property property = property("250000", "300000", "400000");

        assertEquals(new BigDecimal("300000"), Valuation.retained(property));
        assertEquals("Prudent", Valuation.retainedScenario(property));
        assertEquals("Fourchette", Valuation.retainedScenario(property.withSelectedValue(new BigDecimal("350000"))));
        assertEquals("Personnalisée", Valuation.retainedScenario(property.withSelectedValue(new BigDecimal("330000"))));
    }

    @Test
    void sliderValuesAreRoundedAndKeptWithinBounds() {
        Valuation.Scenarios scenarios = Valuation.scenarios(property("250000", "300000", "400000"));

        assertEquals(0, new BigDecimal("450000").compareTo(scenarios.clamp(new BigDecimal("900000000"))));
        assertEquals(0, new BigDecimal("330000").compareTo(scenarios.clamp(new BigDecimal("331234"))));
    }

    @Test
    void netValueRemovesSaleFeesAndDebtOnly() {
        assertEquals(0, new BigDecimal("176000").compareTo(Valuation.net(new BigDecimal("300000"), new BigDecimal("0.08"), new BigDecimal("100000"))));
    }
}
