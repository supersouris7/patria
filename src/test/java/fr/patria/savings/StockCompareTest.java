// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.savings;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockCompareTest {
    private static final LocalDate START = LocalDate.of(2025, 11, 11);

    @Test
    void compoundsAFixedRateOverThePeriod() {
        StockCompare.Line livret = StockCompare.compounding("Livret A", START, START.plusDays(365), new BigDecimal("3"));
        assertEquals(100, livret.values().getFirst().doubleValue(), 0.001);
        assertEquals(103, livret.values().getLast().doubleValue(), 0.01, "un an à 3 % : base 103");
    }

    @Test
    void comparesThePortfolioWithAReferenceInPlainWords() {
        StockCompare.Line mine = new StockCompare.Line("Votre portefeuille", List.of(START, START.plusDays(100), START.plusDays(365)),
                List.of(new BigDecimal("1000"), new BigDecimal("1100"), new BigDecimal("1080")), false);
        StockCompare.Line index = new StockCompare.Line("MSCI World", List.of(START, START.plusDays(365)), List.of(new BigDecimal("50"), new BigDecimal("55")), false);
        String html = StockCompare.chart(mine, List.of(index), StockCompare.Target.WORLD, MarketFeeds.Range.YEAR, "/bourse/comparaison", "").toString();
        assertTrue(html.contains("votre portefeuille fait <b class=\"positive\">+8 %</b> ; MSCI World fait <b class=\"positive\">+10 %</b>"), "phrase de résultat");
        assertTrue(html.contains("Moins bien de 2 points"), "écart en points");
        assertTrue(html.contains(">+10 %</text>") && html.contains("class=\"zero\""), "axe en %, graduations rondes, 0 % marqué");
        assertTrue(html.contains("cible=sp500") && html.contains("cible=savings") && !html.contains("vue=lignes"), "un seul choix : la référence");
        assertTrue(html.contains("Votre portefeuille : +10 %"), "toutes les valeurs d'une date au survol");
        String alone = StockCompare.chart(mine, List.of(), StockCompare.Target.CAC40, MarketFeeds.Range.YEAR, "/bourse/comparaison", "").toString();
        assertTrue(alone.contains("Pas assez de cours"));
    }

    @Test
    void roundsTheAxisSteps() {
        assertEquals(1, StockCompare.niceStep(0.4));
        assertEquals(5, StockCompare.niceStep(3.2));
        assertEquals(10, StockCompare.niceStep(7));
        assertEquals(20, StockCompare.niceStep(12));
    }
}
