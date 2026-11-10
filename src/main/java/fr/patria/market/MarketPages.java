// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import fr.patria.property.Property;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Detail des ventes DVF retenues pour le prix du marche d'un bien. HTML uniquement. */
final class MarketPages {
    private MarketPages() {
    }

    /** Carte des ventes sur le site public DVF (data.gouv.fr), centree sur un point. */
    static String dvfMap(double latitude, double longitude, double zoom) {
        return String.format(Locale.ROOT, "https://explore.data.gouv.fr/fr/immobilier?onglet=carte&filtre=tous&lat=%.5f&lng=%.5f&zoom=%.1f", latitude, longitude, zoom);
    }

    static Html sales(Property property, Optional<MarketSales> result, SalesAnalysis.Filter filter, LocalDate today, String csrf) {
        Html back = Components.backLink("/bien/" + property.id() + "#ventes", property.name());
        if (result.isEmpty() || result.get().sales().isEmpty()) {
            Html content = Html.concat(back, Components.heading("Prix au m²", "Ventes du secteur", property.name(), Html.EMPTY),
                    Components.card("Ventes retenues", null, Html.EMPTY, Html.of(
                            "<p class=\"empty\">Le détail des ventes n'est pas encore disponible : il est enregistré au prochain calcul du prix du marché. "
                                    + "<a href=\"/bien/%s#marche\">Retour à la fiche du bien</a></p>", property.id())));
            return Layout.page("Ventes du secteur · " + property.name() + " · Patria", Layout.Section.REAL_ESTATE, csrf, Html.concat(Layout.estateNavigation("biens"), content));
        }
        MarketSales data = result.get();
        MarketPrice price = data.price();
        List<MarketSale> shown = SalesAnalysis.apply(data.sales(), filter, property.area(), today);
        BigDecimal median = SalesAnalysis.median(shown);
        BigDecimal yours = property.area() == null || property.area().signum() == 0 ? null
                // Comme sur la fiche du bien : valeur retenue actualisee selon l'indice du departement.
                : fr.patria.property.Valuation.retained(property, price.revaluation() == null ? BigDecimal.ONE : price.revaluation())
                .divide(property.area(), 0, java.math.RoundingMode.HALF_UP);
        Html actions = Html.concat(Components.pdfButton("/bien/" + property.id() + "/marche/ventes.pdf" + query(filter)),
                data.latitude() == null ? Html.EMPTY : Html.of("<a class=\"button secondary\" href=\"%s\" target=\"_blank\" rel=\"noopener\">Carte DVF ↗</a>",
                        dvfMap(data.latitude(), data.longitude(), 16)));
        boolean distances = data.sales().stream().anyMatch(sale -> sale.distance() != null);
        Html rows = shown.stream().map(sale -> Html.of("""
                        <tr><td data-sort="%s">%s</td><td>%s</td><td class="num" data-sort="%s">%s</td><td class="num" data-sort="%s">%s</td>\
                        <td class="num" data-sort="%s">%s</td>%s<td><a href="%s" target="_blank" rel="noopener" aria-label="Voir cette vente sur la carte DVF">DVF ↗</a></td></tr>""",
                sale.date(), Format.date(sale.date()), address(sale),
                Format.plain(sale.area()), Format.area(sale.area()), Format.plain(sale.price()), Format.euroWhole(sale.price()),
                Format.plain(sale.perSquareMeter()), Format.perSquareMeter(sale.perSquareMeter()),
                distances ? Html.of("<td class=\"num\" data-sort=\"%s\">%s</td>", sale.distance() == null ? "" : sale.distance(), distance(sale.distance())) : Html.EMPTY,
                dvfMap(sale.latitude(), sale.longitude(), 18))).collect(Html.joining());
        Html table = shown.isEmpty() ? Html.raw("<p class=\"empty\">Aucune vente avec ces filtres.</p>") : Components.table(distances
                ? List.of("Date", "Adresse", "#Surface", "#Prix", "#Prix au m²", "#Distance", "")
                : List.of("Date", "Adresse", "#Surface", "#Prix", "#Prix au m²", ""), rows, true, true);
        String adjustment = price.indexMonth() == null ? "Prix au m² non actualisés."
                : "Prix au m² actualisés à " + Format.month(price.indexMonth()).toLowerCase() + " selon l'évolution des prix du département ; prix de vente d'origine.";
        Html source = Html.of("<p class=\"source\">Ventes de surface comparable %s (%s), %s, valeurs extrêmes écartées. %s<br>"
                        + "Médiane de toutes les ventes retenues : %s, de %s à %s. Source : DVF (DGFiP, Etalab) sur data.gouv.fr · calculé le %s.</p>",
                price.scope() == null ? "" : price.scope().label(), price.place(), price.period(), adjustment,
                Format.perSquareMeter(price.median()), Format.perSquareMeter(price.low()), Format.perSquareMeter(price.high()), Format.date(price.computedOn()));
        Html summary = Components.metrics(4,
                Components.tile("Ventes affichées", null, shown.size() + (filter.active() ? " / " + data.sales().size() : ""), filter.active() ? "avec ces filtres" : "toutes les ventes retenues"),
                Components.tile("Médiane", "Prix au m² médian des ventes affichées, actualisé.", median == null ? Format.DASH : Format.perSquareMeter(median), "la moitié au-dessus, la moitié en dessous"),
                Components.tile("Votre bien", "Valeur retenue ÷ surface.", yours == null ? Format.DASH : Format.perSquareMeter(yours),
                        yours == null || median == null ? "surface inconnue" : gap(yours, median)),
                Components.tile("Période", null, price.period(), price.scope() == null ? "" : price.scope().label()));
        Html content = Html.concat(back,
                Components.heading("Prix au m²", "Ventes du secteur", "Les ventes réelles de logements comparables au vôtre, publiées par l'administration fiscale.", actions),
                summary, filters(property, filter, distances),
                Components.card("Répartition des prix au m²", null, Html.EMPTY, Html.concat(SalesChart.histogram(shown, yours, median),
                        Html.raw("<p class=\"source\">Chaque barre compte les ventes d'une tranche de prix au m². Survolez une barre pour le détail.</p>"))),
                data.latitude() == null ? Html.EMPTY : Components.card("Plan des ventes", null, Html.EMPTY, Html.concat(SalesChart.plan(shown, data.latitude(), data.longitude(), median),
                        Html.raw("<p class=\"source\">Position de chaque vente autour de votre bien, nord en haut. Survolez un point pour le détail ; la carte DVF montre le quartier.</p>"))),
                Components.card("Ventes", shown.size(), Html.EMPTY, Html.concat(table, source)));
        return Layout.page("Ventes du secteur · " + property.name() + " · Patria", Layout.Section.REAL_ESTATE, csrf, Html.concat(Layout.estateNavigation("biens"), content));
    }

    private static String gap(BigDecimal yours, BigDecimal median) {
        BigDecimal percent = yours.subtract(median).multiply(BigDecimal.valueOf(100)).divide(median, 1, java.math.RoundingMode.HALF_UP);
        return Format.signedPercent(percent) + " par rapport à la médiane";
    }

    /** Filtres en GET : la page reste partageable et le bouton PDF reprend les memes filtres. */
    private static Html filters(Property property, SalesAnalysis.Filter filter, boolean distances) {
        Html period = select("periode", "Période", List.of(new String[]{"", "Toutes"}, new String[]{"12", "12 derniers mois"}, new String[]{"24", "24 derniers mois"}),
                filter.months() == null ? "" : String.valueOf(filter.months()));
        Html distance = !distances ? Html.EMPTY : select("distance", "Distance", List.of(new String[]{"", "Toutes"}, new String[]{"500", "500 m"},
                new String[]{"1000", "1 km"}, new String[]{"2000", "2 km"}), filter.maxDistance() == null ? "" : String.valueOf(filter.maxDistance()));
        Html sort = select("tri", "Ordre", java.util.Arrays.stream(SalesAnalysis.Sort.values()).map(value -> new String[]{value.code(), value.label()}).toList(),
                filter.sort().code());
        return Html.of("""
                <form method="get" action="/bien/%s/marche/ventes" class="card section sales-filters"><div class="filters-row">%s\
                <label>Surface min. (m²)<input name="surface_min" value="%s" inputmode="decimal" placeholder="%s"></label>\
                <label>Surface max. (m²)<input name="surface_max" value="%s" inputmode="decimal" placeholder="%s"></label>%s%s\
                <div class="form-actions"><button type="submit">Filtrer</button>%s</div></div></form>""",
                property.id(), period, filter.minArea() == null ? "" : Format.plain(filter.minArea()),
                property.area() == null ? "" : Format.plain(property.area().multiply(new BigDecimal("0.8")).setScale(0, java.math.RoundingMode.HALF_UP)),
                filter.maxArea() == null ? "" : Format.plain(filter.maxArea()),
                property.area() == null ? "" : Format.plain(property.area().multiply(new BigDecimal("1.2")).setScale(0, java.math.RoundingMode.HALF_UP)),
                distance, sort, filter.active() ? Html.of("<a class=\"button secondary\" href=\"/bien/%s/marche/ventes\">Tout afficher</a>", property.id()) : Html.EMPTY);
    }

    private static Html select(String name, String label, List<String[]> options, String selected) {
        return Html.of("<label>%s<select name=\"%s\">%s</select></label>", label, name, options.stream().map(option -> Html.of("<option value=\"%s\"%s>%s</option>",
                option[0], option[0].equals(selected) ? Html.raw(" selected") : Html.EMPTY, option[1])).collect(Html.joining()));
    }

    static String query(SalesAnalysis.Filter filter) {
        List<String> parts = new java.util.ArrayList<>();
        if (filter.months() != null) parts.add("periode=" + filter.months());
        if (filter.minArea() != null) parts.add("surface_min=" + Format.plain(filter.minArea()));
        if (filter.maxArea() != null) parts.add("surface_max=" + Format.plain(filter.maxArea()));
        if (filter.maxDistance() != null) parts.add("distance=" + filter.maxDistance());
        if (filter.sort() != SalesAnalysis.Sort.COMPARABLE) parts.add("tri=" + filter.sort().code());
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    /** Synthese PDF des ventes affichees. */
    static byte[] report(Property property, MarketSales data, SalesAnalysis.Filter filter, LocalDate today) {
        List<MarketSale> shown = SalesAnalysis.apply(data.sales(), filter, property.area(), today);
        BigDecimal median = SalesAnalysis.median(shown);
        fr.patria.ui.PdfReport report = fr.patria.ui.PdfReport.create("Ventes du secteur · " + property.name(),
                data.price().place() + " · " + data.price().period(), today).metrics(List.of(
                fr.patria.ui.PdfReport.Metric.of("Ventes", String.valueOf(shown.size()), filter.active() ? "avec filtres, sur " + data.sales().size() : "toutes les ventes retenues"),
                fr.patria.ui.PdfReport.Metric.of("Médiane", median == null ? Format.DASH : Format.perSquareMeter(median), "prix au m² actualisé"),
                fr.patria.ui.PdfReport.Metric.of("Fourchette (retenues)", Format.perSquareMeter(data.price().low()) + " – " + Format.perSquareMeter(data.price().high()), "1er à 3e quartile"),
                fr.patria.ui.PdfReport.Metric.of("Zone", data.price().scope() == null ? "" : data.price().scope().label(), data.price().place())));
        report.section("Ventes").table(List.of("Date", "Adresse", "#Surface", "#Prix", "#Prix au m²", "#Distance"), shown.stream().map(sale -> fr.patria.ui.PdfReport.row(
                Format.date(sale.date()), sale.address().isBlank() ? Format.DASH : sale.address(), Format.area(sale.area()), Format.euroWhole(sale.price()),
                Format.perSquareMeter(sale.perSquareMeter()), distance(sale.distance()))).toList(), new float[]{1, 3, 0.9f, 1.1f, 1.1f, 0.9f}, false);
        report.note("Source : DVF (DGFiP, Etalab) sur data.gouv.fr. Ventes de logements de surface comparable, valeurs extrêmes écartées, prix au m² actualisés.");
        return report.toBytes();
    }

    /** Adresse de la vente, avec un lien vers Google Maps (pointe sur les coordonnees DVF, plus sures que l'adresse seule). */
    private static Html address(MarketSale sale) {
        String label = sale.address().isBlank() ? "Voir le lieu" : sale.address();
        return Html.of("<a href=\"%s\" target=\"_blank\" rel=\"noopener noreferrer\" title=\"Ouvrir dans Google Maps\">%s ↗</a>", googleMaps(sale.latitude(), sale.longitude()), label);
    }

    static String googleMaps(double latitude, double longitude) {
        return String.format(Locale.ROOT, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", latitude, longitude);
    }

    private static String distance(Integer meters) {
        if (meters == null) return Format.DASH;
        if (meters < 1000) return (meters / 10 * 10) + " m";
        return Format.number(java.math.BigDecimal.valueOf(meters).movePointLeft(3), 1) + " km";
    }
}
