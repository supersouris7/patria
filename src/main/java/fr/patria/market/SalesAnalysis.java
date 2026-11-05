package fr.patria.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Filtres, tri et statistiques des ventes retenues pour un bien. Fonctions pures. */
public final class SalesAnalysis {
    /** Ordre d'affichage des ventes. */
    public enum Sort {
        COMPARABLE("comparables", "Les plus comparables d'abord"),
        RECENT("recentes", "Les plus récentes d'abord"),
        NEAREST("proches", "Les plus proches d'abord"),
        PRICE("prix", "Prix au m² croissant");

        private final String code;
        private final String label;

        Sort(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }

        public static Sort of(String code) {
            for (Sort sort : values()) if (sort.code.equals(code)) return sort;
            return COMPARABLE;
        }
    }

    /**
     * Filtres de la page des ventes ({@code null} : pas de filtre).
     *
     * @param months      ventes des N derniers mois
     * @param minArea     surface minimale en m²
     * @param maxArea     surface maximale en m²
     * @param maxDistance distance maximale au bien, en metres
     */
    public record Filter(Integer months, BigDecimal minArea, BigDecimal maxArea, Integer maxDistance, Sort sort) {
        public static Filter none() {
            return new Filter(null, null, null, null, Sort.COMPARABLE);
        }

        public boolean active() {
            return months != null || minArea != null || maxArea != null || maxDistance != null;
        }
    }

    private SalesAnalysis() {
    }

    public static List<MarketSale> apply(List<MarketSale> sales, Filter filter, BigDecimal area, LocalDate today) {
        LocalDate since = filter.months() == null ? null : today.minusMonths(filter.months());
        List<MarketSale> kept = sales.stream()
                .filter(sale -> since == null || !sale.date().isBefore(since))
                .filter(sale -> filter.minArea() == null || sale.area().compareTo(filter.minArea()) >= 0)
                .filter(sale -> filter.maxArea() == null || sale.area().compareTo(filter.maxArea()) <= 0)
                .filter(sale -> filter.maxDistance() == null || (sale.distance() != null && sale.distance() <= filter.maxDistance()))
                .toList();
        return sorted(kept, filter.sort(), area, today);
    }

    public static List<MarketSale> sorted(List<MarketSale> sales, Sort sort, BigDecimal area, LocalDate today) {
        Comparator<MarketSale> order = switch (sort) {
            case COMPARABLE -> Comparator.comparingDouble(sale -> distanceScore(sale, area, today));
            case RECENT -> Comparator.comparing(MarketSale::date).reversed();
            case NEAREST -> Comparator.comparing(sale -> sale.distance() == null ? Integer.MAX_VALUE : sale.distance());
            case PRICE -> Comparator.comparing(MarketSale::perSquareMeter);
        };
        return sales.stream().sorted(order).toList();
    }

    /**
     * Ecart d'une vente au bien : surface relative (20 % d'ecart = 0,2), plus 0,2 par kilometre, plus 0,1 par annee
     * d'anciennete. Plus c'est petit, plus la vente ressemble au bien.
     */
    static double distanceScore(MarketSale sale, BigDecimal area, LocalDate today) {
        double areaGap = area == null || area.signum() == 0 ? 0 : Math.abs(sale.area().doubleValue() - area.doubleValue()) / area.doubleValue();
        double kilometers = sale.distance() == null ? 1 : sale.distance() / 1000.0;
        double years = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(sale.date(), today) / 365.0);
        return areaGap + 0.2 * kilometers + 0.1 * years;
    }

    /** Mediane des prix au m², {@code null} sans vente. */
    public static BigDecimal median(List<MarketSale> sales) {
        if (sales.isEmpty()) return null;
        List<BigDecimal> values = sales.stream().map(MarketSale::perSquareMeter).sorted().toList();
        int middle = values.size() / 2;
        BigDecimal median = values.size() % 2 == 1 ? values.get(middle)
                : values.get(middle - 1).add(values.get(middle)).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
        return median.setScale(0, RoundingMode.HALF_UP);
    }
}
