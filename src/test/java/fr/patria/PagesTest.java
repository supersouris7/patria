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
    void dashboardSumsRetainedValuesWithoutSlider() {
        Property other = new Property(43, "Studio", "Appartement", "", "", "", BigDecimal.ZERO, new BigDecimal("100000"), LocalDate.of(2020, 1, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(2020, 1, 1), new BigDecimal("0.10"), BigDecimal.ZERO);
        String html = Pages.dashboard(List.of(PROPERTY.withSelectedValue(new BigDecimal("350000")), other), List.of(), Map.of(), List.of(), "csrf");

        assertTrue(html.contains("<a href=\"/bien/42\">Maison &lt;test&gt;</a>"));
        assertFalse(html.contains("type=\"range\""), "pas de curseur sur le patrimoine global");
        assertTrue(html.contains("450\u202f000\u00a0€"), "350 000 + 100 000");
        assertTrue(html.contains("Fourchette"));
        // 350 000 × 0,92 + 100 000 × 0,90 = 412 000
        assertTrue(html.contains("data-derived=\"net\">412\u202f000\u00a0€"));
        assertFalse(html.contains("<test>"), "les noms doivent être échappés");
    }

    @Test
    void propertyPageStartsSliderOnStoredValueAndSavesOnTheProperty() {
        String html = Pages.propertyDetail(PROPERTY.withSelectedValue(new BigDecimal("380000")), List.of(), Map.of(), List.of(), null, "csrf");

        assertTrue(html.contains("data-save=\"/bien/42/valeur\""));
        assertTrue(html.contains("step=\"10000\" value=\"380000\""));
        assertTrue(html.contains("data-estimate-label>Personnalisée<"));
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
