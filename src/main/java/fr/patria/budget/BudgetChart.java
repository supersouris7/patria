// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Graphique des 12 prochains mois, dessine en SVG cote serveur (aucune bibliotheque, compatible avec la politique
 * de securite des pages). Barres empilees par famille de depenses, revenus en repere horizontal sur le meme axe.
 *
 * <p>Couleurs : six familles au plus, palette categorielle validee pour le daltonisme sur le fond sombre (voir
 * {@code .series-N} dans patria.css). Chaque mois porte une info-bulle detaillee ; la liste des mois sous le
 * graphique donne les memes chiffres en texte, et un clic sur un mois l'y ouvre.
 */
final class BudgetChart {
    private static final int WIDTH = 960;
    private static final int HEIGHT = 280;
    private static final int LEFT = 64;
    private static final int RIGHT = 12;
    private static final int TOP = 12;
    private static final int BOTTOM = 34;

    private BudgetChart() {
    }

    static Html render(List<Forecast.Month> months) {
        double max = months.stream().mapToDouble(month -> Math.max(month.income().doubleValue(), month.spending().doubleValue())).max().orElse(0);
        if (max <= 0) return Html.EMPTY;
        double step = niceStep(max / 4);
        double top = Math.ceil(max / step) * step;
        double plotWidth = WIDTH - LEFT - RIGHT;
        double plotHeight = HEIGHT - TOP - BOTTOM;
        double column = plotWidth / months.size();
        double barWidth = Math.min(34, column * 0.5);

        StringBuilder svg = new StringBuilder();
        // Grille et axe : recessifs
        for (double value = 0; value <= top + 0.5; value += step) {
            double y = TOP + plotHeight - value / top * plotHeight;
            svg.append(Html.of("<line class=\"grid\" x1=\"%s\" x2=\"%s\" y1=\"%s\" y2=\"%s\"/>", LEFT, WIDTH - RIGHT, fmt(y), fmt(y)))
                    .append(Html.of("<text class=\"axis\" x=\"%s\" y=\"%s\" text-anchor=\"end\">%s</text>", LEFT - 8, fmt(y + 4),
                            Format.euroWhole(BigDecimal.valueOf(value))));
        }
        for (int i = 0; i < months.size(); i++) {
            Forecast.Month month = months.get(i);
            double x = LEFT + column * i + (column - barWidth) / 2;
            double baseline = TOP + plotHeight;
            double spending = month.spending().doubleValue();
            double barTop = baseline - spending / top * plotHeight;
            String clip = "bar" + i;
            // Barre : extremite haute arrondie, base a plat (le rectangle de decoupe deborde sous la ligne de base)
            if (spending > 0) {
                svg.append(Html.of("<clipPath id=\"%s\"><rect x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\" rx=\"4\"/></clipPath><g clip-path=\"url(#%s)\">",
                        clip, fmt(x), fmt(barTop), fmt(barWidth), fmt(baseline - barTop + 6), clip));
                double y = baseline;
                Map<Category.Family, BigDecimal> families = month.byFamily();
                for (Category.Family family : Category.Family.values()) {
                    BigDecimal amount = families.get(family);
                    if (amount == null || amount.signum() == 0) continue;
                    double height = amount.doubleValue() / top * plotHeight;
                    // 2 px d'ecart entre segments, couleur du fond
                    double drawn = Math.max(height - 2, 1);
                    svg.append(Html.of("<rect class=\"series-%s\" x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"/>",
                            family.ordinal() + 1, fmt(x), fmt(y - height), fmt(barWidth), fmt(drawn)));
                    y -= height;
                }
                svg.append("</g>");
            }
            // Revenus : repere horizontal sur le meme axe
            if (month.income().signum() > 0) {
                double y = baseline - month.income().doubleValue() / top * plotHeight;
                svg.append(Html.of("<line class=\"income\" x1=\"%s\" x2=\"%s\" y1=\"%s\" y2=\"%s\"/>", fmt(x - 7), fmt(x + barWidth + 7), fmt(y), fmt(y)));
            }
            // L'annee accompagne le premier mois et chaque janvier
            String label = Format.shortMonth(month.month()) + (month.month().getMonthValue() == 1 || i == 0 ? " " + month.month().getYear() % 100 : "");
            svg.append(Html.of("<text class=\"axis%s\" x=\"%s\" y=\"%s\" text-anchor=\"middle\">%s</text>", month.balance().signum() < 0 && month.income().signum() > 0 ? " deficit" : "",
                    fmt(x + barWidth / 2), HEIGHT - 12, label));
            // Zone de survol : toute la colonne, plus large que la barre
            // Un clic ouvre le detail du mois dans la liste sous le graphique
            svg.append(Html.of("<a href=\"#%s\" class=\"month-link\" aria-label=\"%s\"><rect class=\"hit\" x=\"%s\" y=\"%s\" width=\"%s\" height=\"%s\"><title>%s</title></rect></a>",
                    anchor(month.month()), "Voir le détail de " + Format.month(month.month()), fmt(LEFT + column * i), TOP, fmt(column), fmt(plotHeight + 6), tooltip(month)));
        }
        String summary = "Revenus et dépenses des 12 prochains mois ; un clic sur un mois ouvre son détail dans la liste ci-dessous.";
        // Sur smartphone, le graphique garde une largeur lisible et defile dans son cadre.
        Html chart = Html.of("<div class=\"chart-scroll\"><svg class=\"chart\" viewBox=\"0 0 %s %s\" role=\"group\" aria-label=\"%s\" preserveAspectRatio=\"xMidYMid meet\">%s</svg></div>",
                WIDTH, HEIGHT, summary, Html.raw(svg.toString()));
        return Html.concat(legend(months), chart);
    }

    /** Legende : familles presentes sur la periode, plus le repere des revenus. */
    private static Html legend(List<Forecast.Month> months) {
        List<Html> items = new ArrayList<>();
        for (Category.Family family : Category.Family.values()) {
            boolean present = months.stream().anyMatch(month -> month.byFamily().containsKey(family));
            if (present) items.add(Html.of("<span class=\"legend-item\"><i class=\"swatch series-%s\"></i>%s</span>", family.ordinal() + 1, family.label()));
        }
        items.add(Html.raw("<span class=\"legend-item\"><i class=\"swatch income-swatch\"></i>Revenus</span>"));
        return Html.of("<div class=\"legend\">%s</div>", items.stream().collect(Html.joining()));
    }

    private static String tooltip(Forecast.Month month) {
        StringBuilder text = new StringBuilder(Format.month(month.month()));
        text.append("\nRevenus : ").append(Format.euroWhole(month.income())).append("\nDépenses : ").append(Format.euroWhole(month.spending()));
        Map<Category.Family, BigDecimal> families = month.byFamily();
        Arrays.stream(Category.Family.values()).filter(families::containsKey)
                .forEach(family -> text.append("\n  · ").append(family.label()).append(" : ").append(Format.euroWhole(families.get(family))));
        text.append("\nReste à vivre : ").append(Format.signedEuroWhole(month.balance()));
        return text.toString();
    }

    /** Identifiant du mois dans la liste detaillee : {@code mois-2026-11}. */
    static String anchor(java.time.YearMonth month) {
        return "mois-" + month;
    }

    /** Pas de graduation lisible : 1, 2, 2,5 ou 5 × 10^n. */
    static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double factor : new double[] {1, 2, 2.5, 5, 10}) if (factor * magnitude >= raw) return factor * magnitude;
        return 10 * magnitude;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
