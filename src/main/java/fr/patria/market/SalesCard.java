// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import fr.patria.property.Property;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Carte « Ventes du secteur » de la fiche d'un bien : apercu des ventes retenues et acces a la page complete. */
public final class SalesCard {
    private SalesCard() {
    }

    /** {@code yours} : prix au m² du bien (valeur retenue ÷ surface), peut etre nul. Vide sans ventes enregistrees. */
    public static Html render(Property property, MarketSales data, BigDecimal yours, LocalDate today) {
        if (data == null || data.sales().isEmpty()) return Html.EMPTY;
        MarketPrice price = data.price();
        List<MarketSale> closest = SalesAnalysis.sorted(data.sales(), SalesAnalysis.Sort.COMPARABLE, property.area(), today).stream().limit(5).toList();
        boolean distances = closest.stream().anyMatch(sale -> sale.distance() != null);
        Html rows = closest.stream().map(sale -> Html.of("<tr><td>%s</td><td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>%s</tr>",
                Format.date(sale.date()), sale.address().isBlank() ? Format.DASH : sale.address(), Format.area(sale.area()), Format.perSquareMeter(sale.perSquareMeter()),
                distances ? Html.of("<td class=\"num\">%s</td>", sale.distance() == null ? Format.DASH : sale.distance() < 1000 ? (sale.distance() / 10 * 10) + " m"
                        : Format.number(BigDecimal.valueOf(sale.distance()).movePointLeft(3), 1) + " km") : Html.EMPTY)).collect(Html.joining());
        Html table = Components.table(distances ? List.of("Date", "Adresse", "#Surface", "#Prix au m²", "#Distance") : List.of("Date", "Adresse", "#Surface", "#Prix au m²"),
                rows, false, false);
        String link = "/bien/" + property.id() + "/marche/ventes";
        Html lead = Html.of("""
                <p class="sales-lead"><b>%s</b> réelles de logements comparables %s, %s : de quoi situer votre bien face au marché.</p>""",
                Format.plural(data.sales().size(), "vente", "ventes"), price.scope() == null ? "" : price.scope().label(), price.period());
        return Html.of("""
                <section class="card section sales-card" id="ventes"><div class="section-title"><h2>Ventes du secteur</h2>%s</div>%s%s
                <h3 class="wizard-group">Les 5 ventes les plus comparables</h3>%s
                <div class="sales-cta"><a class="button" href="%s">Explorer les %s ventes →</a><span class="muted">filtres, plan du quartier, synthèse PDF</span></div></section>""",
                Components.secondaryButton(link, "Voir tout"), lead, SalesChart.histogram(data.sales(), yours, price.median()), table, link, data.sales().size());
    }
}
