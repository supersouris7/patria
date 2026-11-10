// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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

        assertEquals(null, scenarios.market(), "sans prix du marché connu");
        assertEquals(new BigDecimal("412000"), scenarios.withMarket(new BigDecimal("411650")).market(), "prix du marché arrondi à 1 000 €");
        assertEquals("Prix du marché", scenarios.withMarket(new BigDecimal("411650")).nameOf(new BigDecimal("412000")));
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

        // plafond : optimiste × 1,33 (actualisation maximale) + 50 000
        assertEquals(0, new BigDecimal("580000").compareTo(scenarios.clamp(new BigDecimal("900000000"))));
        assertEquals(0, new BigDecimal("331000").compareTo(scenarios.clamp(new BigDecimal("331234"))), "pas de 1 000 €");
    }

    @Test
    void scenariosFollowTheRevaluationButAChosenValueStays() {
        Property property = property("250000", "300000", "400000");
        Valuation.Scenarios revalued = Valuation.scenarios(property, new BigDecimal("1.05"));

        assertEquals(new BigDecimal("320000"), revalued.prudent(), "300 000 × 1,05 = 315 000, arrondi à 10 000");
        assertEquals(new BigDecimal("420000"), revalued.optimistic());
        assertEquals(new BigDecimal("320000"), Valuation.retained(property, new BigDecimal("1.05")));
        assertEquals(new BigDecimal("350000"), Valuation.retained(property.withSelectedValue(new BigDecimal("350000")), new BigDecimal("1.05")));
    }

    @Test
    void netValueRemovesSaleFeesAndDebtOnly() {
        assertEquals(0, new BigDecimal("176000").compareTo(Valuation.net(new BigDecimal("300000"), new BigDecimal("0.08"), new BigDecimal("100000"))));
    }
}
