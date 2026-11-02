package fr.patria;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PagesTest {
    private static final Property PROPERTY = new Property(42, "Maison <test>", "Maison", "Commune", "", "",
            new BigDecimal("95"), new BigDecimal("250000"), LocalDate.of(2020, 1, 1),
            new BigDecimal("300000"), new BigDecimal("400000"), LocalDate.of(2025, 1, 1),
            new BigDecimal("0.08"), BigDecimal.ZERO);

    @Test
    void dashboardShowsPropertyLinksAndScenarios() {
        String html = Pages.dashboard(List.of(PROPERTY), List.of(), Map.of(), List.of(), "csrf");

        assertTrue(html.contains("<a href=\"/bien/42\">Maison &lt;test&gt;</a>"));
        assertTrue(html.contains("data-pessimistic=\"290000\" data-prudent=\"300000\" data-middle=\"350000\" data-optimistic=\"400000\""));
        assertTrue(html.contains("data-derived=\"gain\""));
        assertFalse(html.contains("<test>"), "les noms doivent être échappés");
    }

    @Test
    void dashboardShowsEmptyStateWithoutProperties() {
        String html = Pages.dashboard(List.of(), List.of(), Map.of(), List.of(), "csrf");

        assertTrue(html.contains("Aucun bien pour l'instant"));
    }

    @Test
    void propertyFormKeepsSubmittedValuesAndShowsError() {
        String html = Pages.propertyForm(PROPERTY, Map.of("name", "Nouveau nom", "kind", "Maison"), Pages.Notice.error("Estimation invalide."), "csrf");

        assertTrue(html.contains("value=\"Nouveau nom\""));
        assertTrue(html.contains("Estimation invalide."));
    }

    @Test
    void pagesUseTheSameAssetVersion() {
        String login = Pages.login(false, false, false);
        String page = Pages.message("Titre", "Texte", "csrf");

        assertTrue(login.contains("patria.css?v=" + Pages.ASSET_VERSION));
        assertTrue(page.contains("patria.css?v=" + Pages.ASSET_VERSION));
    }
}
