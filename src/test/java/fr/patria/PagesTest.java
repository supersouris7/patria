package fr.patria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagesTest {
    @TempDir
    Path directory;

    @Test
    void dashboardShowsPropertyLinksAndScenarioValues() {
        Database database = new Database(directory.resolve("patria.sqlite3"));
        Property property = new Property(42, "Maison test", "Maison", "Commune", "", "",
                new BigDecimal("95"), new BigDecimal("250000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("300000"), new BigDecimal("400000"), LocalDate.of(2025, 1, 1),
                new BigDecimal("0.08"), BigDecimal.ZERO);

        String html = Pages.dashboard(database, List.of(property), List.of(), "csrf");

        assertTrue(html.contains("<a href=\"/bien/42\">Maison test</a>"));
        assertTrue(html.contains("<th>Bien</th>"));
        assertTrue(html.contains("Valeur brute</span><strong data-estimate-value data-pessimistic=\"290000\" data-prudent=\"300000\" data-middle=\"350000\" data-optimistic=\"400000\">"));
        assertTrue(html.contains("Plus-value / moins-value</span><strong data-estimate-value data-pessimistic=\"40000\" data-prudent=\"50000\" data-middle=\"100000\" data-optimistic=\"150000\">"));
    }

    @Test
    void dashboardShowsEmptyStateWithoutProperties() {
        String html = Pages.dashboard(new Database(directory.resolve("patria.sqlite3")), List.of(), List.of(), "csrf");

        assertTrue(html.contains("Ajoutez votre premier bien depuis l'administration."));
    }
}
