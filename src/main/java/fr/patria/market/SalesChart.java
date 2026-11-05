package fr.patria.market;

import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Graphiques des ventes du secteur, en SVG dessine par le serveur (aucun service exterieur) : repartition des prix au
 * m² avec le bien et la mediane, et plan des ventes autour du bien. Couleurs de la palette validee de patria.css.
 */
public final class SalesChart {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 220;
    private static final int LEFT = 8;
    private static final int RIGHT = 8;
    private static final int TOP = 26;
    private static final int BOTTOM = 34;

    private SalesChart() {
    }

    /** Pas des barres : environ 12 barres, arrondi a 100, 250, 500 ou 1 000 €/m². */
    static int step(double range) {
        for (int candidate : new int[]{100, 250, 500, 1000, 2000}) if (range / candidate <= 14) return candidate;
        return 5000;
    }

    /**
     * Repartition des prix au m² : une barre par tranche, avec le prix au m² du bien ({@code yours}, peut etre nul) et
     * la mediane des ventes.
     */
    public static Html histogram(List<MarketSale> sales, BigDecimal yours, BigDecimal median) {
        if (sales.isEmpty()) return Html.EMPTY;
        double min = sales.stream().mapToDouble(sale -> sale.perSquareMeter().doubleValue()).min().orElse(0);
        double max = sales.stream().mapToDouble(sale -> sale.perSquareMeter().doubleValue()).max().orElse(0);
        if (yours != null) {
            min = Math.min(min, yours.doubleValue());
            max = Math.max(max, yours.doubleValue());
        }
        int step = step(max - min);
        double start = Math.floor(min / step) * step;
        int bins = Math.max(1, (int) Math.ceil((max - start + 1) / step));
        int[] counts = new int[bins];
        for (MarketSale sale : sales) counts[Math.min(bins - 1, (int) ((sale.perSquareMeter().doubleValue() - start) / step))]++;
        int highest = java.util.Arrays.stream(counts).max().orElse(1);
        double plotWidth = WIDTH - LEFT - RIGHT;
        double plotHeight = HEIGHT - TOP - BOTTOM;
        double column = plotWidth / bins;
        StringBuilder svg = new StringBuilder();
        svg.append(Html.of("<line class=\"grid\" x1=\"%s\" x2=\"%s\" y1=\"%s\" y2=\"%s\"/>", LEFT, WIDTH - RIGHT, fmt(TOP + plotHeight), fmt(TOP + plotHeight)));
        for (int i = 0; i < bins; i++) {
            double height = counts[i] == 0 ? 0 : Math.max(3, plotHeight * counts[i] / highest);
            double x = LEFT + column * i + 1;
            double low = start + step * i;
            String tooltip = Format.euroWhole(BigDecimal.valueOf(low)) + " à " + Format.euroWhole(BigDecimal.valueOf(low + step)) + "/m² : "
                    + Format.plural(counts[i], "vente", "ventes");
            svg.append(Html.of("<rect class=\"bar\" x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" rx=\"3\"><title>%s</title></rect>",
                    fmt(x), fmt(TOP + plotHeight - height), fmt(Math.max(1, column - 2)), fmt(height), tooltip));
            svg.append(Html.of("<rect class=\"hit\" x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"><title>%s</title></rect>",
                    fmt(LEFT + column * i), TOP, fmt(column), fmt(plotHeight), tooltip));
            if (i % Math.max(1, bins / 6) == 0) {
                svg.append(Html.of("<text class=\"axis\" x=\"%s\" y=\"%s\">%s</text>", fmt(LEFT + column * i), HEIGHT - 12,
                        Format.euroWhole(BigDecimal.valueOf(low))));
            }
        }
        // Les deux etiquettes partent de part et d'autre de leur ligne : celle de la plus petite valeur vers la gauche.
        boolean yoursRight = yours == null || median == null || yours.compareTo(median) >= 0;
        if (median != null) svg.append(marker("median", median.doubleValue(), start, step, column, plotHeight, "Médiane " + Format.perSquareMeter(median), 24, !yoursRight));
        if (yours != null) svg.append(marker("yours", yours.doubleValue(), start, step, column, plotHeight, "Votre bien " + Format.perSquareMeter(yours), 12, yoursRight));
        String summary = "Répartition des prix au m² de " + Format.plural(sales.size(), "vente", "ventes")
                + (median == null ? "" : ", médiane " + Format.perSquareMeter(median)) + (yours == null ? "" : ", votre bien " + Format.perSquareMeter(yours)) + ".";
        return Html.of("<div class=\"chart-scroll\"><svg class=\"chart sales-histogram\" viewBox=\"0 0 %s %s\" role=\"img\" aria-label=\"%s\">%s</svg></div>",
                WIDTH, HEIGHT, summary, Html.raw(svg.toString()));
    }

    /** Ligne verticale et son etiquette, a droite de la ligne ({@code toRight}) ou a gauche ; retournee si elle sortait du cadre. */
    private static Html marker(String css, double value, double start, int step, double column, double plotHeight, String label, int y, boolean toRight) {
        double x = LEFT + (value - start) / step * column;
        boolean right = toRight ? x < WIDTH * 0.75 : x < WIDTH * 0.25;
        return Html.of("<line class=\"%s\" x1=\"%s\" x2=\"%s\" y1=\"%s\" y2=\"%s\"/><text class=\"marker-label\" x=\"%s\" y=\"%s\" text-anchor=\"%s\">%s</text>",
                css, fmt(x), fmt(x), y + 4, fmt(TOP + plotHeight), fmt(right ? x + 5 : x - 5), y, right ? "start" : "end", label);
    }

    /**
     * Plan des ventes autour du bien (nord en haut), anneaux a 500 m, 1 km et 2 km. Couleur : prix au m² sous ou
     * au-dessus de la mediane. Vide si la position du bien est inconnue.
     */
    public static Html plan(List<MarketSale> sales, Double latitude, Double longitude, BigDecimal median) {
        if (latitude == null || longitude == null || sales.isEmpty()) return Html.EMPTY;
        int size = 420;
        double center = size / 2.0;
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(latitude));
        double farthest = 300;
        for (MarketSale sale : sales) farthest = Math.max(farthest, Math.hypot((sale.longitude() - longitude) * metersPerLon, (sale.latitude() - latitude) * 110_540));
        double scale = (center - 18) / farthest;
        StringBuilder svg = new StringBuilder();
        for (int ring : new int[]{500, 1000, 2000}) {
            if (ring > farthest * 1.05) continue;
            double radius = ring * scale;
            svg.append(Html.of("<circle class=\"ring\" cx=\"%s\" cy=\"%s\" r=\"%s\"/><text class=\"axis\" x=\"%s\" y=\"%s\" text-anchor=\"middle\">%s</text>",
                    fmt(center), fmt(center), fmt(radius), fmt(center), fmt(center - radius - 4), ring < 1000 ? ring + " m" : ring / 1000 + " km"));
        }
        for (MarketSale sale : sales) {
            double x = center + (sale.longitude() - longitude) * metersPerLon * scale;
            double y = center - (sale.latitude() - latitude) * 110_540 * scale;
            boolean above = median != null && sale.perSquareMeter().compareTo(median) > 0;
            svg.append(Html.of("<circle class=\"sale %s\" cx=\"%s\" cy=\"%s\" r=\"4.5\"><title>%s · %s · %s · %s</title></circle>", above ? "above" : "below",
                    fmt(x), fmt(y), sale.address().isBlank() ? "Vente" : sale.address(), Format.date(sale.date()), Format.area(sale.area()),
                    Format.perSquareMeter(sale.perSquareMeter())));
        }
        svg.append(Html.of("<path class=\"home\" d=\"M%s %sl7 7m-7 0l7-7\" transform=\"translate(-3.5 -3.5)\"/>", fmt(center), fmt(center)));
        svg.append(Html.of("<circle class=\"home-ring\" cx=\"%s\" cy=\"%s\" r=\"9\"/><text class=\"marker-label\" x=\"%s\" y=\"%s\">Votre bien</text>",
                fmt(center), fmt(center), fmt(center + 12), fmt(center + 4)));
        svg.append(Html.raw("<text class=\"axis\" x=\"8\" y=\"18\">N ↑</text>"));
        Html legend = median == null ? Html.EMPTY : Html.of("""
                <div class="legend"><span class="legend-item"><i class="swatch sale-below"></i>Prix au m² sous la médiane (%s)</span>\
                <span class="legend-item"><i class="swatch sale-above"></i>Au-dessus de la médiane</span><span class="legend-item">✕ Votre bien</span></div>""",
                Format.perSquareMeter(median));
        return Html.of("<div class=\"sales-plan\">%s<svg class=\"chart plan\" viewBox=\"0 0 %s %s\" role=\"img\" aria-label=\"%s\">%s</svg></div>", legend, size, size,
                "Plan de " + Format.plural(sales.size(), "vente", "ventes") + " autour du bien, nord en haut.", Html.raw(svg.toString()));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
