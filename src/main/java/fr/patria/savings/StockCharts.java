package fr.patria.savings;

import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Courbes de la page Bourse, en SVG rendu par le serveur : le cours d'un titre (prix de revient en pointille) et la
 * valeur reconstituee du portefeuille (lignes actuelles × cours passes, converties en euros). Fragments charges a la
 * demande par app.js, avec le choix de la periode.
 */
final class StockCharts {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 220;
    private static final int LEFT = 64;
    private static final int RIGHT = 12;
    private static final int TOP = 12;
    private static final int BOTTOM = 26;

    /** Une serie datee, en euros ou dans la devise du titre. */
    record Series(List<LocalDate> dates, List<BigDecimal> values) {
        boolean isEmpty() {
            return dates.size() < 2;
        }
    }

    private StockCharts() {
    }

    /** Cours d'un titre sur la periode, avec son prix de revient unitaire (en pointille) quand il est connu. */
    static Html position(SavingsRepository.Position position, MarketFeeds.History history, MarketFeeds.Range range, String href) {
        Series series = new Series(history.dates(), history.closes());
        BigDecimal unit = history.currency().equals("EUR") && position.quantity().signum() > 0
                ? position.cost().divide(position.quantity(), 4, RoundingMode.HALF_UP) : null;
        String unitLabel = history.currency().equals("EUR") ? "€" : history.currency();
        return frame(series, range, href, unit, value -> Format.number(value, 2) + " " + unitLabel,
                "Cours de " + position.label() + (history.currency().equals("EUR") ? "" : " (en " + history.currency() + ")"));
    }

    /**
     * Valeur du portefeuille jour par jour : pour chaque date, quantite × dernier cours connu de chaque ligne, converti
     * en euros, plus les liquidites. Les lignes sans historique comptent a leur prix de revient.
     */
    static Series portfolio(List<SavingsRepository.Position> positions, BigDecimal cash, MarketFeeds.Range range, Function<String, MarketFeeds.History> histories) {
        List<MarketFeeds.History> lines = new ArrayList<>();
        List<MarketFeeds.History> rates = new ArrayList<>();
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>();
        for (SavingsRepository.Position position : positions) {
            MarketFeeds.History history = histories.apply(position.symbol());
            lines.add(history);
            rates.add(history.isEmpty() || history.currency().equals("EUR") ? null : histories.apply(history.currency() + "EUR=X"));
            dates.addAll(history.dates());
        }
        List<LocalDate> days = new ArrayList<>(dates);
        List<BigDecimal> values = new ArrayList<>();
        for (LocalDate day : days) {
            BigDecimal total = cash;
            for (int index = 0; index < positions.size(); index++) {
                SavingsRepository.Position position = positions.get(index);
                MarketFeeds.History history = lines.get(index);
                BigDecimal price = at(history, day);
                MarketFeeds.History rate = rates.get(index);
                if (price != null && rate != null) {
                    BigDecimal fx = at(rate, day);
                    price = fx == null ? null : price.multiply(fx);
                }
                total = total.add(price == null ? position.cost() : price.multiply(position.quantity()));
            }
            values.add(total.setScale(2, RoundingMode.HALF_UP));
        }
        return new Series(days, values);
    }

    static Html portfolioChart(Series series, MarketFeeds.Range range, String href) {
        return frame(series, range, href, null, Format::euroWhole, "Valeur du portefeuille");
    }

    /** Dernier cours connu a cette date ; avant le premier cours, le premier (pas de saut au debut de la courbe). */
    private static BigDecimal at(MarketFeeds.History history, LocalDate day) {
        if (history.isEmpty()) return null;
        int index = java.util.Collections.binarySearch(history.dates(), day);
        if (index < 0) index = Math.max(0, -index - 2);
        return history.closes().get(index);
    }

    private static Html frame(Series series, MarketFeeds.Range range, String href, BigDecimal reference, Function<BigDecimal, String> format, String title) {
        Html periods = java.util.Arrays.stream(MarketFeeds.Range.values()).map(option -> Html.of("<a href=\"%s%speriode=%s\" data-fragment-link%s>%s</a>",
                href, href.contains("?") ? "&" : "?", option.code(), Html.raw(option == range ? " class=\"active\" aria-current=\"true\"" : ""), option.label()))
                .collect(Html.joining());
        if (series.isEmpty()) {
            return Html.of("<div class=\"stock-chart\"><nav class=\"segmented small\" aria-label=\"Période\">%s</nav><p class=\"muted\">Pas d'historique de cours "
                    + "disponible (titre inconnu de Yahoo Finance, ou mode hors ligne).</p></div>", periods);
        }
        BigDecimal first = series.values().getFirst();
        BigDecimal last = series.values().getLast();
        BigDecimal change = first.signum() == 0 ? BigDecimal.ZERO : last.subtract(first).multiply(BigDecimal.valueOf(100)).divide(first, 1, RoundingMode.HALF_UP);
        String tone = change.signum() > 0 ? "positive" : change.signum() < 0 ? "negative" : "";
        BigDecimal min = series.values().stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = series.values().stream().max(BigDecimal::compareTo).orElseThrow();
        if (reference != null) {
            min = min.min(reference);
            max = max.max(reference);
        }
        double low = min.doubleValue();
        double high = max.doubleValue();
        double pad = Math.max((high - low) * 0.08, Math.abs(high) * 0.005 + 0.01);
        low -= pad;
        high += pad;
        double plotWidth = WIDTH - LEFT - RIGHT;
        double plotHeight = HEIGHT - TOP - BOTTOM;
        int count = series.dates().size();
        StringBuilder svg = new StringBuilder();
        for (int tick = 0; tick <= 2; tick++) {
            double value = low + (high - low) * tick / 2;
            double y = TOP + plotHeight - plotHeight * tick / 2;
            svg.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", LEFT, WIDTH - RIGHT, y, y));
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>", LEFT - 8, y + 4,
                    escape(format.apply(BigDecimal.valueOf(value)))));
        }
        StringBuilder path = new StringBuilder();
        double step = plotWidth / (count - 1);
        for (int index = 0; index < count; index++) {
            double x = LEFT + index * step;
            double y = TOP + plotHeight - plotHeight * (series.values().get(index).doubleValue() - low) / (high - low);
            path.append(index == 0 ? "M" : "L").append(String.format(Locale.ROOT, "%.1f %.1f", x, y));
            svg.append(String.format(Locale.ROOT, "<rect class=\"hit\" x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%.1f\"><title>%s · %s</title></rect>",
                    x - step / 2, TOP, Math.max(step, 1), plotHeight, escape(Format.date(series.dates().get(index))), escape(format.apply(series.values().get(index)))));
        }
        if (reference != null) {
            double y = TOP + plotHeight - plotHeight * (reference.doubleValue() - low) / (high - low);
            svg.append(String.format(Locale.ROOT, "<line class=\"reference\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"><title>Prix de revient : %s</title></line>",
                    LEFT, WIDTH - RIGHT, y, y, escape(format.apply(reference))));
        }
        svg.append("<path class=\"line\" d=\"").append(path).append("\"/>");
        for (int index : new int[]{0, count / 2, count - 1}) {
            svg.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"%s\">%s</text>", LEFT + index * step, HEIGHT - 6,
                    index == 0 ? "start" : index == count - 1 ? "end" : "middle", escape(Format.date(series.dates().get(index)))));
        }
        return Html.of("""
                <div class="stock-chart"><div class="stock-chart-head"><nav class="segmented small" aria-label="Période">%s</nav>\
                <span class="stock-change %s">%s sur %s</span>%s</div>
                <svg viewBox="0 0 %s %s" role="img" aria-label="%s">%s</svg></div>""",
                periods, tone, Format.signedPercent(change), range.label(),
                reference == null ? Html.EMPTY : Html.raw("<span class=\"stock-legend\"><i></i>prix de revient</span>"),
                WIDTH, HEIGHT, title + ", " + range.label(), Html.raw(svg.toString()));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
