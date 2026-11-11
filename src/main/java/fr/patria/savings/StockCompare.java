// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.savings;

import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Comparaison des performances en base 100 (100 au debut de la periode) : les lignes detenues entre elles, le
 * portefeuille face a un indice, ou face a l'epargne et a l'inflation. Une seule echelle, une couleur par serie dans un
 * ordre fixe (8 au plus), legende et derniere valeur ecrites, valeur au survol.
 */
final class StockCompare {
    /** Vue choisie. */
    enum View {
        LINES("lignes", "Mes lignes"), INDEX("indice", "Face à un indice"), SAVINGS("epargne", "Face à l'épargne");

        final String code;
        final String label;

        View(String code, String label) {
            this.code = code;
            this.label = label;
        }

        static View parse(String value) {
            for (View view : values()) if (view.code.equals(value)) return view;
            return LINES;
        }
    }

    /** Indices proposes (symboles Yahoo Finance). */
    enum Benchmark {
        WORLD("CW8.PA", "MSCI World (ETF Amundi)"), SP500("^GSPC", "S&P 500"), CAC40("^FCHI", "CAC 40"), STOXX50("^STOXX50E", "Euro Stoxx 50");

        final String symbol;
        final String label;

        Benchmark(String symbol, String label) {
            this.symbol = symbol;
            this.label = label;
        }

        static Benchmark parse(String value) {
            for (Benchmark benchmark : values()) if (benchmark.name().equalsIgnoreCase(value == null ? "" : value)) return benchmark;
            return WORLD;
        }
    }

    /** Une courbe : dates et valeurs brutes, ramenees en base 100 au trace ; {@code dashed} : reference (portefeuille, taux). */
    record Line(String label, List<LocalDate> dates, List<BigDecimal> values, boolean dashed) {
        boolean usable() {
            return dates.size() >= 2 && values.getFirst().signum() > 0;
        }
    }

    static final int MAX_SERIES = 8;
    private static final int WIDTH = 720;
    private static final int HEIGHT = 240;
    private static final int LEFT = 44;
    private static final int RIGHT = 70;
    private static final int TOP = 12;
    private static final int BOTTOM = 26;

    private StockCompare() {
    }

    /** Placement a taux fixe (livret, taux moyen) ou hausse des prix, capitalise jour par jour sur la periode. */
    static Line compounding(String label, LocalDate start, LocalDate end, BigDecimal yearlyPercent) {
        List<LocalDate> dates = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(start, end);
        long stride = Math.max(1, days / 60);
        // Un point tous les quelques jours, et toujours le dernier jour de la periode.
        for (long day = 0; day <= days; day = day == days ? days + 1 : Math.min(days, day + stride)) {
            dates.add(start.plusDays(day));
            values.add(BigDecimal.valueOf(100 * Math.pow(1 + yearlyPercent.doubleValue() / 100, day / 365.0)));
        }
        return new Line(label, dates, values, true);
    }

    static Html chart(List<Line> lines, View view, Benchmark benchmark, MarketFeeds.Range range, String href, String note) {
        Html views = java.util.Arrays.stream(View.values()).map(option -> link(href, option.code, benchmark, range, option.label, option == view)).collect(Html.joining());
        Html periods = java.util.Arrays.stream(MarketFeeds.Range.values()).map(option -> link(href, view.code, benchmark, option, option.label(), option == range))
                .collect(Html.joining());
        Html indices = view != View.INDEX ? Html.EMPTY : Html.of("<nav class=\"segmented small\" aria-label=\"Indice\">%s</nav>", java.util.Arrays.stream(Benchmark.values())
                .map(option -> link(href, view.code, option, range, option.label, option == benchmark)).collect(Html.joining()));
        Html controls = Html.of("""
                <div class="stock-chart-head"><nav class="segmented small" aria-label="Comparer">%s</nav><nav class="segmented small" aria-label="Période">%s</nav></div>%s""",
                views, periods, indices);
        List<Line> usable = lines.stream().filter(Line::usable).limit(MAX_SERIES).toList();
        if (usable.size() < 2) {
            return Html.of("<div class=\"stock-chart\">%s<p class=\"muted\">Pas assez de cours pour comparer (titre inconnu de Yahoo Finance, ou mode hors ligne).</p></div>",
                    controls);
        }
        LocalDate start = usable.stream().map(line -> line.dates().getFirst()).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = usable.stream().map(line -> line.dates().getLast()).max(LocalDate::compareTo).orElseThrow();
        double span = Math.max(1, ChronoUnit.DAYS.between(start, end));
        double low = 100;
        double high = 100;
        for (Line line : usable) {
            double base = line.values().getFirst().doubleValue();
            for (BigDecimal value : line.values()) {
                low = Math.min(low, 100 * value.doubleValue() / base);
                high = Math.max(high, 100 * value.doubleValue() / base);
            }
        }
        double pad = Math.max((high - low) * 0.08, 1);
        low -= pad;
        high += pad;
        double plotWidth = WIDTH - LEFT - RIGHT;
        double plotHeight = HEIGHT - TOP - BOTTOM;
        double bottomValue = low;
        double range100 = high - low;
        java.util.function.DoubleUnaryOperator y = value -> TOP + plotHeight - plotHeight * (value - bottomValue) / range100;
        StringBuilder svg = new StringBuilder();
        for (int tick = 0; tick <= 3; tick++) {
            double value = low + (high - low) * tick / 3;
            svg.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", LEFT, WIDTH - RIGHT, y.applyAsDouble(value), y.applyAsDouble(value)));
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>", LEFT - 6, y.applyAsDouble(value) + 4,
                    Math.round(value)));
        }
        svg.append(String.format(Locale.ROOT, "<line class=\"base100\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", LEFT, WIDTH - RIGHT, y.applyAsDouble(100), y.applyAsDouble(100)));
        Html legend = Html.EMPTY;
        for (int index = 0; index < usable.size(); index++) {
            Line line = usable.get(index);
            String slot = "c" + (index + 1);
            double base = line.values().getFirst().doubleValue();
            StringBuilder points = new StringBuilder();
            for (int point = 0; point < line.dates().size(); point++) {
                double x = LEFT + plotWidth * ChronoUnit.DAYS.between(start, line.dates().get(point)) / span;
                points.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y.applyAsDouble(100 * line.values().get(point).doubleValue() / base)));
            }
            svg.append(String.format(Locale.ROOT, "<polyline class=\"compare-line %s%s\" points=\"%s\"/>", slot, line.dashed() ? " dashed" : "", points.toString().strip()));
            double last = 100 * line.values().getLast().doubleValue() / base;
            double change = last - 100;
            String text = (change >= 0 ? "+" : "−") + Format.number(BigDecimal.valueOf(Math.abs(change)).setScale(1, java.math.RoundingMode.HALF_UP), 1) + " %";
            double endX = LEFT + plotWidth * ChronoUnit.DAYS.between(start, line.dates().getLast()) / span;
            svg.append(String.format(Locale.ROOT, "<circle class=\"compare-dot %s\" cx=\"%.1f\" cy=\"%.1f\" r=\"4\"><title>%s : %s sur la période</title></circle>",
                    slot, endX, y.applyAsDouble(last), escape(line.label()), escape(text)));
            legend = Html.concat(legend, Html.of("<span class=\"legend-item\"><i class=\"swatch %s\"></i>%s <b>%s</b></span>", slot, line.label(), text));
        }
        svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%d\" text-anchor=\"start\">%s</text>", LEFT, HEIGHT - 6, escape(Format.date(start))));
        svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%d\" text-anchor=\"end\">%s</text>", WIDTH - RIGHT, HEIGHT - 6, escape(Format.date(end))));
        return Html.of("""
                <div class="stock-chart">%s<div class="chart-legend">%s</div>\
                <svg class="compare-chart" viewBox="0 0 %s %s" role="img" aria-label="Comparaison des performances, base 100">%s</svg>\
                <p class="muted small">Base 100 au début de la période : 110 veut dire +10 %. %s</p></div>""",
                controls, legend, WIDTH, HEIGHT, Html.raw(svg.toString()), note);
    }

    private static Html link(String href, String view, Benchmark benchmark, MarketFeeds.Range range, String label, boolean active) {
        return Html.of("<a href=\"%s?vue=%s&amp;indice=%s&amp;periode=%s\" data-fragment-link%s>%s</a>", href, view, benchmark.name().toLowerCase(Locale.ROOT), range.code(),
                Html.raw(active ? " class=\"active\" aria-current=\"true\"" : ""), label);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
