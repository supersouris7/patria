// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.function.Function;

/**
 * Courbes mensuelles de la section « Votre evolution » : une echelle, graduations rondes, la serie principale en
 * trait epais, une reference en pointille, legende avec la derniere valeur, toutes les valeurs d'un mois au survol.
 */
final class TrendChart {
    /** Une courbe ; {@code css} : mine (principale) ou ref1 (reference) ; {@code dashed} : en pointille. */
    record Series(String label, String css, SortedMap<YearMonth, BigDecimal> values, boolean dashed) {
    }

    private static final int WIDTH = 720;
    private static final int HEIGHT = 220;
    private static final int LEFT = 64;
    private static final int RIGHT = 16;
    private static final int TOP = 12;
    private static final int BOTTOM = 26;

    private TrendChart() {
    }

    static Html render(List<Series> all, Function<BigDecimal, String> format) {
        List<Series> series = all.stream().filter(line -> line.values().size() >= 2).toList();
        if (series.isEmpty()) {
            // Un seul mois : les valeurs ecrites, la courbe viendra avec les mois suivants.
            List<Series> single = all.stream().filter(line -> line.values().size() == 1).toList();
            if (single.isEmpty()) return Html.raw("<p class=\"muted\">Pas encore assez de mois pour une courbe.</p>");
            return Html.of("<p>%s</p><p class=\"muted small\">La courbe apparaîtra dès le deuxième mois.</p>", Html.raw(String.join(" · ", single.stream()
                    .map(line -> Format.month(line.values().firstKey()) + " — " + escape(line.label()) + " <b>" + escape(format.apply(line.values().get(line.values().firstKey()))) + "</b>")
                    .toList())));
        }
        YearMonth start = series.stream().map(line -> line.values().firstKey()).min(YearMonth::compareTo).orElseThrow();
        YearMonth end = series.stream().map(line -> line.values().lastKey()).max(YearMonth::compareTo).orElseThrow();
        double span = Math.max(1, ChronoUnit.MONTHS.between(start, end));
        double low = series.stream().flatMap(line -> line.values().values().stream()).mapToDouble(BigDecimal::doubleValue).min().orElse(0);
        double high = series.stream().flatMap(line -> line.values().values().stream()).mapToDouble(BigDecimal::doubleValue).max().orElse(1);
        if (low > 0 && low < high * 0.5) low = 0;
        double step = niceStep(Math.max(high - low, 1) / 4);
        double bottom = Math.floor(low / step) * step;
        double top = Math.ceil(high / step) * step;
        if (top <= bottom) top = bottom + step;
        double plotWidth = WIDTH - LEFT - RIGHT;
        double plotHeight = HEIGHT - TOP - BOTTOM;
        double floor = bottom;
        double extent = top - bottom;
        java.util.function.DoubleUnaryOperator y = value -> TOP + plotHeight * (1 - (value - floor) / extent);
        java.util.function.ToDoubleFunction<YearMonth> x = month -> LEFT + plotWidth * ChronoUnit.MONTHS.between(start, month) / span;
        StringBuilder svg = new StringBuilder();
        for (double tick = bottom; tick <= top + step / 2; tick += step) {
            svg.append(String.format(Locale.ROOT, "<line class=\"%s\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", Math.abs(tick) < step / 1000 ? "zero" : "grid",
                    LEFT, WIDTH - RIGHT, y.applyAsDouble(tick), y.applyAsDouble(tick)));
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>", LEFT - 8, y.applyAsDouble(tick) + 4,
                    escape(format.apply(BigDecimal.valueOf(tick)))));
        }
        for (YearMonth month : new YearMonth[] {start, start.plusMonths((long) (span / 2)), end}) {
            String anchor = month.equals(start) ? "start" : month.equals(end) ? "end" : "middle";
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"%s\">%s</text>", x.applyAsDouble(month), HEIGHT - 8, anchor,
                    escape(Format.month(month))));
        }
        Html legend = Html.EMPTY;
        for (Series line : series.reversed()) {
            StringBuilder points = new StringBuilder();
            line.values().forEach((month, value) -> points.append(String.format(Locale.ROOT, "%.1f,%.1f ", x.applyAsDouble(month), y.applyAsDouble(value.doubleValue()))));
            String css = line.css() + (line.dashed() ? " rate" : "");
            svg.append(String.format(Locale.ROOT, "<polyline class=\"compare-line %s\" points=\"%s\"/>", css, points.toString().strip()));
            BigDecimal last = line.values().get(line.values().lastKey());
            svg.append(String.format(Locale.ROOT, "<circle class=\"compare-dot %s\" cx=\"%.1f\" cy=\"%.1f\" r=\"4\"/>", line.css(), x.applyAsDouble(line.values().lastKey()),
                    y.applyAsDouble(last.doubleValue())));
        }
        for (Series line : series) {
            legend = Html.concat(legend, Html.of("<span class=\"legend-item\"><i class=\"swatch %s\"></i>%s <b>%s</b></span>", line.css() + (line.dashed() ? " rate" : ""),
                    line.label(), format.apply(line.values().get(line.values().lastKey()))));
        }
        // Survol : une colonne par mois, avec la valeur de chaque courbe.
        long months = ChronoUnit.MONTHS.between(start, end) + 1;
        double width = plotWidth / Math.max(1, months - 1);
        for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            StringBuilder title = new StringBuilder(Format.month(month));
            for (Series line : series) {
                BigDecimal value = line.values().get(month);
                if (value != null) title.append('\n').append(line.label()).append(" : ").append(format.apply(value));
            }
            svg.append(String.format(Locale.ROOT, "<rect class=\"hit\" x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%.1f\"><title>%s</title></rect>",
                    Math.max(LEFT, x.applyAsDouble(month) - width / 2), TOP, width, plotHeight, escape(title.toString())));
        }
        return Html.of("""
                <div class="chart-legend">%s</div><div class="chart-scroll"><svg class="compare-chart" viewBox="0 0 %s %s" role="img" aria-label="Évolution mois par mois">%s</svg></div>""",
                legend, WIDTH, HEIGHT, Html.raw(svg.toString()));
    }

    /** Pas rond : 1, 2, 5 × une puissance de 10. */
    static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1e-9))));
        for (double candidate : new double[] {1, 2, 5, 10}) if (candidate * magnitude >= raw) return candidate * magnitude;
        return 10 * magnitude;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
