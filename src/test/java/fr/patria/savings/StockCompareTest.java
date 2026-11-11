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
    void drawsEachLineInBase100WithItsChange() {
        StockCompare.Line etf = new StockCompare.Line("ETF Monde", List.of(START, START.plusDays(100), START.plusDays(365)),
                List.of(new BigDecimal("50"), new BigDecimal("55"), new BigDecimal("60")), false);
        StockCompare.Line action = new StockCompare.Line("Action", List.of(START, START.plusDays(365)), List.of(new BigDecimal("200"), new BigDecimal("150")), false);
        String html = StockCompare.chart(List.of(etf, action), StockCompare.View.LINES, StockCompare.Benchmark.WORLD, MarketFeeds.Range.YEAR, "/bourse/comparaison", "").toString();
        assertTrue(html.contains("ETF Monde <b>+20 %") && html.contains("Action <b>−25 %"), "variation sur la période, base 100");
        assertTrue(html.contains("compare-line c1") && html.contains("compare-line c2"), "une couleur par série, dans l'ordre");
        assertTrue(html.contains("vue=indice") && html.contains("periode=5a"), "vues et périodes");
        String alone = StockCompare.chart(List.of(etf), StockCompare.View.INDEX, StockCompare.Benchmark.CAC40, MarketFeeds.Range.YEAR, "/bourse/comparaison", "").toString();
        assertTrue(alone.contains("Pas assez de cours") && alone.contains("CAC 40"), "une seule série : rien à comparer, choix de l'indice visible");
    }
}
