// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import fr.patria.property.PropertyKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calcul du prix au m² de reference a partir des ventes DVF. Fonctions pures, sans reseau.
 *
 * <ol>
 *   <li>ventes du meme type (appartement / maison) ;</li>
 *   <li>surface comparable : ±40 % de celle du bien, si elle est connue ;</li>
 *   <li>prix au m² plausible (300 € a 40 000 €) ;</li>
 *   <li>actualisation de chaque vente avec l'indice du departement ;</li>
 *   <li>zone : 500 m autour de l'adresse, sinon 1 km, sinon 2 km, sinon la commune ; un rayon n'est retenu qu'avec
 *       au moins {@value #MIN_SALES} ventes comparables, pour que la mediane soit representative ;</li>
 *   <li>valeurs aberrantes ecartees (regle de Tukey : hors 1,5 ecart interquartile) ;</li>
 *   <li>mediane et quartiles, arrondis a 10 €.</li>
 * </ol>
 */
final class MarketAnalysis {
    static final int MIN_SALES = 30;
    static final int MIN_SALES_COMMUNE = 5;
    private static final BigDecimal MIN_PER_SQM = new BigDecimal("300");
    private static final BigDecimal MAX_PER_SQM = new BigDecimal("40000");
    private static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.4");
    private static final BigDecimal TEN = BigDecimal.TEN;

    /** Point geographique du bien ; {@code precise} vaut faux si seule la commune est connue. */
    record Location(double latitude, double longitude, String citycode, String label, boolean precise) {
    }

    private record Adjusted(Sale sale, BigDecimal perSquareMeter, double distance) {
    }

    /** Prix calcule et ventes retenues (apres ecart des valeurs aberrantes), de la plus recente a la plus ancienne. */
    record Result(MarketPrice price, List<MarketSale> sales) {
    }

    private MarketAnalysis() {
    }

    static Result analyse(long propertyId, String fingerprint, LocalDate today, PropertyKind kind, BigDecimal area,
                               Location location, String place, List<Sale> sales, PriceIndex index, String period) {
        List<Adjusted> comparable = new ArrayList<>();
        for (Sale sale : sales) {
            if (sale.kind() != kind) continue;
            if (area != null && !similarArea(sale.area(), area)) continue;
            BigDecimal perSquareMeter = sale.pricePerSquareMeter();
            if (perSquareMeter.compareTo(MIN_PER_SQM) < 0 || perSquareMeter.compareTo(MAX_PER_SQM) > 0) continue;
            comparable.add(new Adjusted(sale, perSquareMeter.multiply(index.factor(YearMonth.from(sale.date()))),
                    distance(location.latitude(), location.longitude(), sale.latitude(), sale.longitude())));
        }
        MarketPrice.Scope scope = MarketPrice.Scope.COMMUNE;
        List<Adjusted> selected = comparable;
        if (location.precise()) {
            for (MarketPrice.Scope candidate : List.of(MarketPrice.Scope.RADIUS_500, MarketPrice.Scope.RADIUS_1000, MarketPrice.Scope.RADIUS_2000)) {
                double radius = candidate.meters();
                List<Adjusted> near = comparable.stream().filter(sale -> sale.distance() <= radius).toList();
                if (near.size() >= MIN_SALES) {
                    scope = candidate;
                    selected = near;
                    break;
                }
            }
        }
        List<BigDecimal> values = withoutOutliers(selected.stream().map(Adjusted::perSquareMeter).sorted().toList());
        if (values.size() < (scope == MarketPrice.Scope.COMMUNE ? MIN_SALES_COMMUNE : MIN_SALES)) {
            return new Result(new MarketPrice(propertyId, fingerprint, today, MarketPrice.Status.INSUFFICIENT, null, null, null, values.size(), scope, place, period,
                    index.latestMonth(), "Trop peu de ventes comparables (" + values.size() + ") pour estimer un prix fiable.", null), List.of());
        }
        // Les valeurs gardees forment un intervalle continu : une vente est retenue si son prix est entre la plus basse et la plus haute.
        BigDecimal lowest = values.getFirst();
        BigDecimal highest = values.getLast();
        boolean precise = location.precise();
        List<MarketSale> retained = selected.stream()
                .filter(sale -> sale.perSquareMeter().compareTo(lowest) >= 0 && sale.perSquareMeter().compareTo(highest) <= 0)
                .map(sale -> new MarketSale(sale.sale().date(), sale.sale().address(), sale.sale().area(), sale.sale().price(),
                        sale.perSquareMeter().setScale(0, RoundingMode.HALF_UP), precise ? (int) Math.round(sale.distance()) : null,
                        sale.sale().latitude(), sale.sale().longitude()))
                .sorted(Comparator.comparing(MarketSale::date).reversed()).toList();
        return new Result(new MarketPrice(propertyId, fingerprint, today, MarketPrice.Status.OK, round(quantile(values, 0.5)), round(quantile(values, 0.25)),
                round(quantile(values, 0.75)), values.size(), scope, place, period, index.latestMonth(), null, null), retained);
    }

    private static boolean similarArea(BigDecimal saleArea, BigDecimal area) {
        BigDecimal margin = area.multiply(AREA_TOLERANCE);
        return saleArea.compareTo(area.subtract(margin)) >= 0 && saleArea.compareTo(area.add(margin)) <= 0;
    }

    /** Regle de Tukey : ecarte les valeurs au-dela de 1,5 ecart interquartile. */
    static List<BigDecimal> withoutOutliers(List<BigDecimal> sorted) {
        if (sorted.size() < 4) return sorted;
        BigDecimal q1 = quantile(sorted, 0.25);
        BigDecimal q3 = quantile(sorted, 0.75);
        BigDecimal fence = q3.subtract(q1).multiply(new BigDecimal("1.5"));
        BigDecimal low = q1.subtract(fence);
        BigDecimal high = q3.add(fence);
        return sorted.stream().filter(value -> value.compareTo(low) >= 0 && value.compareTo(high) <= 0).sorted(Comparator.naturalOrder()).toList();
    }

    /** Quantile par interpolation lineaire sur une liste triee. */
    static BigDecimal quantile(List<BigDecimal> sorted, double q) {
        double position = q * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        BigDecimal fraction = BigDecimal.valueOf(position - lower);
        return sorted.get(lower).add(sorted.get(upper).subtract(sorted.get(lower)).multiply(fraction));
    }

    /** Distance en metres entre deux points (formule de haversine). */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadius * Math.asin(Math.sqrt(a));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN);
    }
}
