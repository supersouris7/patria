package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesAnalysisTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static MarketSale sale(String date, int area, int perSqm, int distance) {
        return new MarketSale(LocalDate.parse(date), "", BigDecimal.valueOf(area), BigDecimal.valueOf((long) area * perSqm), BigDecimal.valueOf(perSqm), distance, 45, 5);
    }

    private final List<MarketSale> sales = List.of(sale("2026-06-01", 68, 4000, 900), sale("2024-01-01", 70, 5000, 100), sale("2026-09-01", 120, 3000, 50),
            sale("2025-12-01", 66, 4500, 200));

    @Test
    void mostComparableFirst() {
        List<MarketSale> sorted = SalesAnalysis.sorted(sales, SalesAnalysis.Sort.COMPARABLE, BigDecimal.valueOf(68), TODAY);
        assertEquals(66, sorted.getFirst().area().intValue(), "surface proche, proche, récente");
        assertEquals(120, sorted.getLast().area().intValue(), "surface très différente en dernier");
    }

    @Test
    void filtersAndMedian() {
        SalesAnalysis.Filter filter = new SalesAnalysis.Filter(12, BigDecimal.valueOf(60), BigDecimal.valueOf(80), 1000, SalesAnalysis.Sort.PRICE);
        List<MarketSale> shown = SalesAnalysis.apply(sales, filter, BigDecimal.valueOf(68), TODAY);
        assertEquals(List.of(4000, 4500), shown.stream().map(sale -> sale.perSquareMeter().intValue()).toList(), "12 mois, 60 à 80 m², 1 km, prix croissant");
        assertEquals(0, new BigDecimal("4250").compareTo(SalesAnalysis.median(shown)));
        assertTrue(SalesChart.histogram(shown, BigDecimal.valueOf(4200), BigDecimal.valueOf(4250)).toString().contains("Votre bien"));
        assertTrue(SalesChart.plan(shown, 45.0, 5.0, BigDecimal.valueOf(4250)).toString().contains("class=\"sale below\""));
    }
}
