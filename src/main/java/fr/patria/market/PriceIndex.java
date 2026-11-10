// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evolution des prix dans le departement (mediane mensuelle DVF par type de bien), lissee sur 12 mois.
 * Sert a actualiser une vente ancienne : prix × indice(dernier mois) / indice(mois de la vente).
 */
final class PriceIndex {
    /** Mediane du prix au m² et nombre de ventes d'un mois. */
    record Point(YearMonth month, BigDecimal median, int sales) {
    }

    private static final BigDecimal MIN_FACTOR = new BigDecimal("0.75");
    private static final BigDecimal MAX_FACTOR = new BigDecimal("1.33");
    private final TreeMap<YearMonth, BigDecimal> smoothed = new TreeMap<>();

    PriceIndex(Iterable<Point> points) {
        TreeMap<YearMonth, Point> byMonth = new TreeMap<>();
        for (Point point : points) if (point.median() != null && point.sales() > 0) byMonth.put(point.month(), point);
        for (YearMonth month : byMonth.keySet()) {
            BigDecimal weighted = BigDecimal.ZERO;
            int weight = 0;
            for (Map.Entry<YearMonth, Point> entry : byMonth.subMap(month.minusMonths(11), true, month, true).entrySet()) {
                weighted = weighted.add(entry.getValue().median().multiply(BigDecimal.valueOf(entry.getValue().sales())));
                weight += entry.getValue().sales();
            }
            smoothed.put(month, weighted.divide(BigDecimal.valueOf(weight), 2, RoundingMode.HALF_UP));
        }
    }

    static PriceIndex none() {
        return new PriceIndex(java.util.List.of());
    }

    /** Dernier mois connu, ou {@code null} sans indice. */
    YearMonth latestMonth() {
        return smoothed.isEmpty() ? null : smoothed.lastKey();
    }

    /** Coefficient d'actualisation d'une vente, borne entre 0,75 et 1,33 ; 1 si l'indice ne couvre pas le mois. */
    BigDecimal factor(YearMonth saleMonth) {
        if (smoothed.isEmpty()) return BigDecimal.ONE;
        Map.Entry<YearMonth, BigDecimal> at = smoothed.floorEntry(saleMonth);
        if (at == null) at = smoothed.firstEntry();
        BigDecimal factor = smoothed.lastEntry().getValue().divide(at.getValue(), 4, RoundingMode.HALF_UP);
        return factor.max(MIN_FACTOR).min(MAX_FACTOR);
    }
}
