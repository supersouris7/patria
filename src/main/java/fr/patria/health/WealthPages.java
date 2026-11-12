// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.portfolio.Portfolio;
import fr.patria.savings.Savings;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Bilan patrimonial : ce que le foyer possede (actifs) et ce qu'il doit (passifs), a la valeur du jour. Les biens
 * immobiliers ne figurent que pour qui voit l'immobilier.
 */
final class WealthPages {
    /** Une ligne du bilan : libelle, precision, montant, page de detail. */
    record Row(String label, String detail, BigDecimal amount, String href) {
    }

    private WealthPages() {
    }

    static Html page(Savings.Overview savings, Portfolio.Overview estate, String csrf) {
        BigDecimal available = sum(savings, Kind.AVAILABLE);
        BigDecimal longTerm = sum(savings, Kind.LONG_TERM);
        BigDecimal stocks = sum(savings, Kind.STOCKS);
        List<Row> assets = new ArrayList<>(List.of(
                new Row("Comptes et livrets", "disponible tout de suite", available, "/epargne"),
                new Row("Épargne long terme", "assurance-vie, PER, PEL", longTerm, "/epargne"),
                new Row("Bourse et cryptos", "au cours du jour", stocks, "/bourse")));
        List<Row> liabilities = new ArrayList<>();
        if (estate != null) {
            assets.add(new Row("Immobilier", "valeur retenue de " + Format.plural(estate.properties().size(), "bien", "biens"), estate.value(), "/immobilier/biens"));
            liabilities.add(new Row("Prêts immobiliers", "capital restant dû", estate.debt(), "/immobilier/prets"));
        }
        BigDecimal totalAssets = assets.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLiabilities = liabilities.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = totalAssets.subtract(totalLiabilities);
        Html tiles = Components.metrics(3,
                tile("Ce que vous possédez", "Comptes, épargne, bourse et biens, à la valeur du jour (patrimoine brut).", Format.euroWhole(totalAssets), "accent",
                        "patrimoine brut"),
                tile("Ce que vous devez", "Capital restant dû des prêts immobiliers.", Format.euroWhole(totalLiabilities), totalLiabilities.signum() > 0 ? "warning" : "",
                        totalAssets.signum() == 0 || totalLiabilities.signum() == 0 ? "dettes" : "dettes · " + percent(totalLiabilities, totalAssets) + " de ce que vous possédez"),
                tile("Patrimoine net", "Ce que vous possédez − ce que vous devez.", Format.euroWhole(net), net.signum() >= 0 ? "positive" : "negative",
                        "avant frais de vente et impôts"));
        Html method = Components.disclosure("Comment c'est calculé", Html.raw("""
                <p><b>Patrimoine net = ce que vous possédez − ce que vous devez.</b> C'est ce qui vous resterait si vous remboursiez toutes vos dettes aujourd'hui,
                sans rien vendre. C'est la définition habituelle (celle des banques et de l'INSEE).</p>
                <ul><li>Les biens comptent pour leur <b>valeur retenue</b>, avant frais de vente et impôts sur la plus-value. Ce qu'il resterait après une vente
                est dans Immobilier → Synthèse (« valeur nette »), un chiffre plus bas.</li>
                <li>Un bien acheté à crédit compte deux fois, en sens inverse : sa valeur dans ce que vous possédez, son capital restant dû dans ce que vous devez.
                Rembourser une mensualité ne change donc presque pas le patrimoine net ; c'est la part de capital remboursée qui l'augmente.</li>
                <li>La bourse est comptée au cours du jour, les comptes et livrets à leur solde. Les crédits à la consommation ne sont pas comptés : seule leur
                mensualité est connue.</li></ul>"""), false);
        Html content = Html.concat(Components.heading("Patrimoine", "Patrimoine", "Ce que vous possédez et ce que vous devez, à la valeur du jour.", Html.EMPTY),
                tiles, method, composition(assets, totalAssets),
                Html.of("<div class=\"wealth-grid\">%s%s</div>", table("Ce que vous possédez", assets, totalAssets), table("Ce que vous devez", liabilities, totalLiabilities)));
        return Layout.page("Patrimoine · Patria", Layout.Section.WEALTH, csrf, content);
    }

    private enum Kind { AVAILABLE, LONG_TERM, STOCKS }

    private static BigDecimal sum(Savings.Overview savings, Kind kind) {
        return savings.lines().stream().filter(line -> switch (kind) {
            case STOCKS -> line.account().kind().stocks();
            case AVAILABLE -> !line.account().kind().stocks() && line.account().kind().available();
            case LONG_TERM -> !line.account().kind().stocks() && !line.account().kind().available();
        }).map(Savings.Line::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Repartition des actifs : une barre empilee, une couleur par famille, legende avec la part de chacune. */
    private static Html composition(List<Row> assets, BigDecimal total) {
        if (total.signum() <= 0) return Html.EMPTY;
        List<Row> shown = assets.stream().filter(row -> row.amount().signum() > 0).toList();
        Html bar = shown.stream().map(row -> Html.of("<i class=\"series-%s\" style=\"flex-grow:%s\" title=\"%s · %s\"></i>", assets.indexOf(row) + 1,
                row.amount().setScale(0, RoundingMode.HALF_UP).toPlainString(), row.label(), Format.euroWhole(row.amount()))).collect(Html.joining());
        Html legend = shown.stream().map(row -> Html.of("<li><i class=\"series-%s\"></i>%s <b>%s</b></li>", assets.indexOf(row) + 1, row.label(), percent(row.amount(), total)))
                .collect(Html.joining());
        return Components.card("Répartition de ce que vous possédez", null, Html.EMPTY, Html.of("<div class=\"wealth-bar\" role=\"img\" aria-label=\"Répartition des actifs\">%s</div>"
                + "<ul class=\"chart-legend\">%s</ul>", bar, legend));
    }

    private static Html table(String title, List<Row> rows, BigDecimal total) {
        Html body = rows.isEmpty() ? Html.raw("<p class=\"empty\">Rien à rembourser.</p>") : Html.of("""
                <table class="data wealth-table"><tbody>%s</tbody><tfoot><tr><th>Total</th><th class="num">%s</th></tr></tfoot></table>""",
                rows.stream().map(row -> Html.of("<tr><td><a href=\"%s\">%s</a><small>%s</small></td><td class=\"num\">%s</td></tr>", row.href(), row.label(), row.detail(),
                        Format.euroWhole(row.amount()))).collect(Html.joining()), Format.euroWhole(total));
        return Components.card(title, null, Html.EMPTY, body);
    }

    private static Html tile(String label, String hint, String value, String tone, String sub) {
        return Html.of("<article class=\"metric\">%s<strong class=\"%s\">%s</strong>%s</article>", Components.label(label, hint), tone, value,
                sub == null ? Html.EMPTY : Html.of("<small>%s</small>", sub));
    }

    private static String percent(BigDecimal part, BigDecimal total) {
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.HALF_UP) + " %";
    }
}
