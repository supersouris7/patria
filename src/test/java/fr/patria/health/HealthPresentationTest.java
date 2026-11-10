// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HealthPresentationTest {
    @Test
    void severityTakesPriorityOverDomainWithoutDuplicatingChecks() {
        Check advice = Check.of("advice", Check.Domain.FINANCES, Check.Level.INFO, "Conseil finances", "Conseil");
        Check warning = Check.of("warning", Check.Domain.FINANCES, Check.Level.WARN, "Attention finances", "Attention");
        Check urgent = Check.of("urgent", Check.Domain.TAXES, Check.Level.BAD, "Urgence fiscale", "Urgence");
        Check good = Check.of("good", Check.Domain.PROPERTIES, Check.Level.GOOD, "Bien à jour", "Bien");
        String page = HealthPages.page("Foyer", List.of(advice, warning, urgent, good), List.of(), true, fr.patria.ui.Html.EMPTY, "csrf").toString();
        assertTrue(page.indexOf("Urgence fiscale") < page.indexOf("Attention finances"));
        assertTrue(page.indexOf("Attention finances") < page.indexOf("Conseil finances"));
        assertTrue(page.contains("<details><summary>Points rassurants</summary>"));
        for (Check check : List.of(advice, warning, urgent, good)) {
            assertEquals(page.indexOf(check.title()), page.lastIndexOf(check.title()), "point affiche une seule fois");
        }
    }
}
