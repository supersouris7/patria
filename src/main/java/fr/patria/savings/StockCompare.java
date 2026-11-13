// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.savings;

import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mon portefeuille face a une reference : un indice, ou le Livret A et l'inflation. Une phrase donne le resultat
 * (mieux ou moins bien, de combien) ; le graphique montre l'evolution en % depuis le debut de la periode, avec des
 * graduations rondes, le portefeuille en trait epais, les taux en pointille, et toutes les valeurs d'une date au survol.
 */
final class StockCompare {
    /** A quoi comparer le portefeuille (indices : symboles Yahoo Finance, hors dividendes). */
    enum Target {
        WORLD("CW8.PA", "MSCI World", "Environ 1 500 grandes entreprises de 23 pays riches : le repère le plus courant d'un placement en actions diversifié."),
        SP500("^GSPC", "S&P 500", "Les 500 plus grandes entreprises américaines, qui pèsent près des deux tiers du MSCI World."),
        CAC40("^FCHI", "CAC 40", "Les 40 plus grandes entreprises françaises : utile si vous détenez surtout des actions françaises."),
        SAVINGS(null, "Livret A et inflation", "Ce que l'argent aurait rapporté sans risque sur un Livret A, et ce qu'il faut gagner pour ne pas perdre en pouvoir d'achat.");

        final String symbol;
        final String label;
        final String why;

        Target(String symbol, String label, String why) {
            this.symbol = symbol;
            this.label = label;
            this.why = why;
        }

        static Target parse(String value) {
            for (Target target : values()) if (target.name().equalsIgnoreCase(value == null ? "" : value)) return target;
            return WORLD;
        }
    }

    /** Une courbe : dates et valeurs brutes (cours, valeur), ramenees en % depuis le debut ; {@code rate} : taux capitalise, en pointille. */
    record Line(String label, List<LocalDate> dates, List<BigDecimal> values, boolean rate) {
        boolean usable() {
            return dates.size() >= 2 && values.getFirst().signum() > 0;
        }

        double change(int index) {
            return 100 * (values.get(index).doubleValue() / values.getFirst().doubleValue() - 1);
        }

        /** Variation a cette date (dernier point connu avant elle). */
        double changeAt(LocalDate day) {
            int index = java.util.Collections.binarySearch(dates, day);
            if (index < 0) index = Math.max(0, -index - 2);
            return change(index);
        }

        double last() {
            return change(values.size() - 1);
        }
    }

    private static final int WIDTH = 720;
    private static final int HEIGHT = 260;
    private static final int LEFT = 52;
    private static final int RIGHT = 16;
    private static final int TOP = 14;
    private static final int BOTTOM = 28;
    private static final int COLUMNS = 48;

    private StockCompare() {
    }

    /** Placement a taux fixe (livret) ou hausse des prix, capitalise jour par jour sur la periode. */
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

    /** {@code mine} : le portefeuille ; {@code references} : l'indice, ou le Livret A et l'inflation. */
    static Html chart(Line mine, List<Line> references, Target target, MarketFeeds.Range range, String href, String note) {
        Html targets = java.util.Arrays.stream(Target.values()).map(option -> link(href, option, range, option.label, option == target)).collect(Html.joining());
        Html periods = java.util.Arrays.stream(MarketFeeds.Range.values()).map(option -> link(href, target, option, option.label(), option == range))
                .collect(Html.joining());
        Html controls = Html.of("""
                <div class="compare-controls"><div class="compare-target">%s<nav class="segmented small" aria-label="Comparer à">%s</nav></div>\
                <nav class="segmented small" aria-label="Période">%s</nav></div>""", help(), targets, periods);
        List<Line> others = references.stream().filter(Line::usable).toList();
        if (!mine.usable() || others.isEmpty()) {
            return Html.of("<div class=\"stock-chart\">%s<p class=\"muted\">Pas assez de cours pour comparer (titre inconnu de Yahoo Finance, ou mode hors ligne).</p></div>",
                    controls);
        }
        List<Line> lines = new ArrayList<>();
        lines.add(mine);
        lines.addAll(others);
        LocalDate start = lines.stream().map(line -> line.dates().getFirst()).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = lines.stream().map(line -> line.dates().getLast()).max(LocalDate::compareTo).orElseThrow();
        double span = Math.max(1, ChronoUnit.DAYS.between(start, end));

        // Graduations rondes (pas de 1, 2, 5, 10, 20, 50 %), 0 % toujours visible.
        double low = 0;
        double high = 0;
        for (Line line : lines) {
            for (int index = 0; index < line.values().size(); index++) {
                low = Math.min(low, line.change(index));
                high = Math.max(high, line.change(index));
            }
        }
        double step = niceStep((high - low) / 4);
        double bottom = Math.floor(low / step) * step;
        double top = Math.ceil(high / step) * step;
        if (top - bottom < step) top = bottom + step;
        double plotWidth = WIDTH - LEFT - RIGHT;
        double plotHeight = HEIGHT - TOP - BOTTOM;
        double extent = top - bottom;
        double floor = bottom;
        java.util.function.DoubleUnaryOperator y = value -> TOP + plotHeight * (1 - (value - floor) / extent);
        java.util.function.ToDoubleFunction<LocalDate> x = day -> LEFT + plotWidth * ChronoUnit.DAYS.between(start, day) / span;

        StringBuilder svg = new StringBuilder();
        for (double tick = bottom; tick <= top + step / 2; tick += step) {
            boolean zero = Math.abs(tick) < step / 1000;
            svg.append(String.format(Locale.ROOT, "<line class=\"%s\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", zero ? "zero" : "grid", LEFT, WIDTH - RIGHT,
                    y.applyAsDouble(tick), y.applyAsDouble(tick)));
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>", LEFT - 8, y.applyAsDouble(tick) + 4,
                    escape(percent(tick, 0))));
        }
        for (LocalDate day : new LocalDate[] {start, start.plusDays((long) (span / 2)), end}) {
            String anchor = day.equals(start) ? "start" : day.equals(end) ? "end" : "middle";
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"%s\">%s</text>", x.applyAsDouble(day), HEIGHT - 8, anchor,
                    escape(range == MarketFeeds.Range.MONTH ? Format.date(day) : Format.month(java.time.YearMonth.from(day)))));
        }
        // Les references d'abord, le portefeuille par-dessus.
        for (int index = lines.size() - 1; index >= 0; index--) {
            Line line = lines.get(index);
            String css = css(index, line);
            StringBuilder points = new StringBuilder();
            for (int point = 0; point < line.dates().size(); point++) {
                points.append(String.format(Locale.ROOT, "%.1f,%.1f ", x.applyAsDouble(line.dates().get(point)), y.applyAsDouble(line.change(point))));
            }
            svg.append(String.format(Locale.ROOT, "<polyline class=\"compare-line %s\" points=\"%s\"/>", css, points.toString().strip()));
            svg.append(String.format(Locale.ROOT, "<circle class=\"compare-dot %s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%d\"/>", css,
                    x.applyAsDouble(line.dates().getLast()), y.applyAsDouble(line.last()), index == 0 ? 5 : 4));
        }
        Html legend = Html.EMPTY;
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            legend = Html.concat(legend, Html.of("<span class=\"legend-item\"><i class=\"swatch %s\"></i>%s <b class=\"%s\">%s</b></span>",
                    css(index, line), Character.toUpperCase(line.label().charAt(0)) + line.label().substring(1), line.rate() ? "" : tone(line.last()),
                    percent(line.last(), 1)));
        }
        // Survol : une colonne invisible par tranche de temps, avec toutes les valeurs a cette date.
        double width = plotWidth / COLUMNS;
        for (int column = 0; column < COLUMNS; column++) {
            LocalDate day = start.plusDays(Math.round(span * (column + 0.5) / COLUMNS));
            StringBuilder title = new StringBuilder(Format.date(day));
            for (Line line : lines) title.append('\n').append(line.label()).append(" : ").append(percent(line.changeAt(day), 1));
            svg.append(String.format(Locale.ROOT, "<rect class=\"hit\" x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%.1f\"><title>%s</title></rect>",
                    LEFT + column * width, TOP, width, plotHeight, escape(title.toString())));
        }

        Line reference = others.getFirst();
        double gap = mine.last() - reference.last();
        Html verdict = Html.of("""
                <p class="compare-verdict">Sur %s, votre portefeuille fait <b class="%s">%s</b> ; %s fait <b class="%s">%s</b>. \
                <span class="%s">%s de %s point%s.</span></p>""",
                range.label(), tone(mine.last()), percent(mine.last(), 1), reference.label(), reference.rate() ? "" : tone(reference.last()), percent(reference.last(), 1),
                gap >= 0 ? "positive" : "negative", gap >= 0 ? "Mieux" : "Moins bien", Format.number(BigDecimal.valueOf(Math.abs(gap)).setScale(1, RoundingMode.HALF_UP), 1),
                Math.abs(gap) >= 2 ? "s" : "");
        return Html.of("""
                <div class="stock-chart">%s%s<div class="chart-legend">%s</div>\
                <div class="chart-scroll"><svg class="compare-chart" viewBox="0 0 %s %s" role="img" aria-label="Évolution en pourcentage depuis le début de la période">%s</svg></div>\
                <p class="muted small">Évolution depuis le début de la période ; survolez la courbe pour les valeurs d'une date. %s</p></div>""",
                controls, verdict, legend, WIDTH, HEIGHT, Html.raw(svg.toString()), note);
    }

    /** Classe d'une courbe : le portefeuille, puis les references dans l'ordre (taux en pointille). */
    private static String css(int index, Line line) {
        return index == 0 ? "mine" : "ref" + index + (line.rate() ? " rate" : "");
    }

    /** Pas de graduation rond, au moins 1 % : 1, 2, 5, 10, 20, 50… */
    static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
        for (double candidate : new double[] {1, 2, 5, 10}) if (candidate * magnitude >= raw) return candidate * magnitude;
        return 10 * magnitude;
    }

    private static String percent(double value, int decimals) {
        BigDecimal rounded = BigDecimal.valueOf(Math.abs(value)).setScale(decimals, RoundingMode.HALF_UP);
        String sign = rounded.signum() == 0 ? "" : value > 0 ? "+" : "−";
        return sign + Format.number(rounded, decimals) + " %";
    }

    private static String tone(double value) {
        return value > 0.05 ? "positive" : value < -0.05 ? "negative" : "";
    }

    private static Html link(String href, Target target, MarketFeeds.Range range, String label, boolean active) {
        return Html.of("<a href=\"%s?cible=%s&amp;periode=%s\" data-fragment-link title=\"%s\"%s>%s</a>", href, target.name().toLowerCase(Locale.ROOT), range.code(),
                target.why, Html.raw(active ? " class=\"active\" aria-current=\"true\"" : ""), label);
    }

    /** Bulle « i » : pourquoi ces points de comparaison, et pourquoi faire mieux que l'inflation. */
    private static Html help() {
        Html lines = java.util.Arrays.stream(Target.values()).map(target -> Html.of("<li><b>%s</b> : %s</li>", target.label, target.why)).collect(Html.joining());
        return Html.of("""
                <details class="metric-help compare-help"><summary class="muted small">Comparer à <span class="help-dot" aria-hidden="true">i</span></summary>\
                <div><p>Un repère dit si vos choix valent l'effort : un fonds indiciel mondial s'achète en un clic, pour presque rien.</p><ul>%s</ul>\
                <p><b>Pourquoi faire mieux que l'inflation ?</b> Les prix montent chaque année : un placement qui rapporte moins que l'inflation \
                vous fait perdre du pouvoir d'achat, même si son solde augmente. À 2 % d'inflation, 10 000 € qui dorment n'achètent plus \
                que l'équivalent d'environ 8 200 € au bout de 10 ans.</p></div></details>""", lines);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
