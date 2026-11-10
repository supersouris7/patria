// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.budget.Category;
import fr.patria.budget.Forecast;
import fr.patria.common.Frequency;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthInsightsTest {
    private static Forecast.Average monthly(String label, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new Forecast.Average(label, "", Category.OTHER, value, Frequency.MONTHLY, value, Forecast.Origin.BUDGET, null);
    }

    @Test
    void twoStreamingServicesAreFlaggedWithTheirYearlyCost() {
        List<Check> checks = HealthInsights.duplicates(List.of(monthly("Netflix", "13"), monthly("Disney+", "9"), monthly("Spotify", "11")));
        assertEquals(1, checks.size(), "la musique seule ne fait pas doublon");
        assertTrue(checks.getFirst().title().contains("Netflix, Disney+"));
        assertTrue(checks.getFirst().detail().contains("264"), "22 € × 12");
    }
}
