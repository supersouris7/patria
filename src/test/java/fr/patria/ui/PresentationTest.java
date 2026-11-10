// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PresentationTest {
    @Test
    void helpAndDisclosuresAreNativeAccessibleAndEscaped() {
        String help = Components.label("Reste <à> vivre", "Calcul <privé>").toString();
        assertTrue(help.contains("<summary class=\"metric-label\">"));
        assertTrue(help.contains("Reste &lt;à&gt; vivre"));
        assertTrue(help.contains("Calcul &lt;privé&gt;"));
        assertFalse(help.contains("title="), "l'aide s'ouvre au clavier et au toucher");
        assertTrue(Components.disclosure("Ajouter", Html.text("contenu"), true).toString().contains("<details open>"));
        assertTrue(Components.disclosure("Ajouter", Html.EMPTY, false).toString().contains("<details>"));
    }
}
