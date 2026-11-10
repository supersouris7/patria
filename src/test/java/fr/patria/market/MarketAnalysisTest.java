// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;

import fr.patria.property.PropertyKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketAnalysisTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final MarketAnalysis.Location HOME = new MarketAnalysis.Location(45.0, 5.0, "38185", "Rue X Grenoble", true);
    /** ~0,001° de latitude ≈ 111 m. */
    private static final double DEGREE_100M = 0.0009;

    private static Sale sale(int perSqm, int area, double metersNorth, PropertyKind kind) {
        return new Sale(LocalDate.of(2025, 1, 1), BigDecimal.valueOf((long) perSqm * area), BigDecimal.valueOf(area), kind,
                HOME.latitude() + metersNorth / 100 * DEGREE_100M, HOME.longitude(), "");
    }

    private static MarketPrice analyse(List<Sale> sales, BigDecimal area, PriceIndex index) {
        return MarketAnalysis.analyse(1, "f", TODAY, PropertyKind.APARTMENT, area, HOME, "Grenoble", sales, index, "2023–2025").price();
    }

    @Test
    void usesNearbyComparableSalesAndIgnoresOthers() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 31; i++) sales.add(sale(4000 + i * 10, 60, 300, PropertyKind.APARTMENT));  // proches
        for (int i = 0; i < 12; i++) sales.add(sale(2000, 60, 3000, PropertyKind.APARTMENT));           // à 3 km
        for (int i = 0; i < 12; i++) sales.add(sale(9000, 60, 100, PropertyKind.HOUSE));                // maisons
        for (int i = 0; i < 12; i++) sales.add(sale(9000, 200, 100, PropertyKind.APARTMENT));           // trop grands

        MarketPrice price = analyse(sales, new BigDecimal("60"), PriceIndex.none());

        assertEquals(MarketPrice.Status.OK, price.status());
        assertEquals(MarketPrice.Scope.RADIUS_500, price.scope());
        assertEquals(31, price.salesCount());
        assertEquals(0, new BigDecimal("4150").compareTo(price.median()));
    }

    @Test
    void tooFewNearbySalesWidensTheRadiusStepByStep() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 15; i++) sales.add(sale(5000, 60, 300, PropertyKind.APARTMENT));   // 15 à 300 m : insuffisant
        for (int i = 0; i < 20; i++) sales.add(sale(4000, 60, 1500, PropertyKind.APARTMENT));  // à 1,5 km

        MarketPrice price = analyse(sales, null, PriceIndex.none());

        assertEquals(MarketPrice.Scope.RADIUS_2000, price.scope(), "500 m et 1 km ont moins de 30 ventes");
        assertEquals(35, price.salesCount());
    }

    @Test
    void widensToCommuneAndDropsOutliers() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 8; i++) sales.add(sale(3000, 60, 5000, PropertyKind.APARTMENT));
        sales.add(sale(30000, 60, 5000, PropertyKind.APARTMENT));

        MarketPrice price = analyse(sales, null, PriceIndex.none());

        assertEquals(MarketPrice.Scope.COMMUNE, price.scope());
        assertEquals(8, price.salesCount(), "la vente à 30 000 €/m² est écartée");
        assertEquals(0, new BigDecimal("3000").compareTo(price.median()));
    }

    @Test
    void reportsInsufficientData() {
        assertEquals(MarketPrice.Status.INSUFFICIENT, analyse(List.of(sale(3000, 60, 100, PropertyKind.APARTMENT)), null, PriceIndex.none()).status());
    }

    @Test
    void olderSalesAreBroughtUpToDateWithTheDepartmentIndex() {
        PriceIndex index = new PriceIndex(List.of(
                new PriceIndex.Point(YearMonth.of(2025, 1), new BigDecimal("3000"), 100),
                new PriceIndex.Point(YearMonth.of(2026, 6), new BigDecimal("3600"), 100)));
        assertEquals(0, new BigDecimal("1.0000").compareTo(index.factor(YearMonth.of(2026, 6))));
        // indice lissé sur 12 mois : juin 2026 ne contient que lui-même, janvier 2025 aussi
        assertEquals(0, new BigDecimal("1.2000").compareTo(index.factor(YearMonth.of(2025, 1))));

        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 30; i++) sales.add(sale(3000, 60, 100, PropertyKind.APARTMENT));
        assertEquals(0, new BigDecimal("3600").compareTo(analyse(sales, null, index).median()));
    }
}
