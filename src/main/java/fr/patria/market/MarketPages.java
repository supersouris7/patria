package fr.patria.market;

import fr.patria.property.Property;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
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

    static Html sales(Property property, Optional<MarketSales> result, String csrf) {
        Html back = Components.backLink("/bien/" + property.id() + "#marche", property.name());
        Html content;
        if (result.isEmpty() || result.get().sales().isEmpty()) {
            content = Html.concat(back, Components.heading("Prix au m²", "Ventes du secteur", property.name(), Html.EMPTY),
                    Components.card("Ventes retenues", null, Html.EMPTY, Html.of(
                            "<p class=\"empty\">Le détail des ventes n'est pas encore disponible : il est enregistré au prochain calcul du prix du marché. "
                                    + "<a href=\"/bien/%s#marche\">Retour à la fiche du bien</a></p>", property.id())));
        } else {
            MarketSales data = result.get();
            MarketPrice price = data.price();
            Html map = data.latitude() == null ? Html.EMPTY
                    : Html.of("<a class=\"button secondary\" href=\"%s\" target=\"_blank\" rel=\"noopener\">Voir sur la carte DVF ↗</a>",
                    dvfMap(data.latitude(), data.longitude(), 16));
            boolean distances = data.sales().stream().anyMatch(sale -> sale.distance() != null);
            Html rows = data.sales().stream().map(sale -> Html.of("""
                            <tr><td data-sort="%s">%s</td><td>%s</td><td class="num" data-sort="%s">%s</td><td class="num" data-sort="%s">%s</td>\
                            <td class="num" data-sort="%s">%s</td>%s<td><a href="%s" target="_blank" rel="noopener" aria-label="Voir cette vente sur la carte DVF">DVF ↗</a></td></tr>""",
                    sale.date(), Format.date(sale.date()), address(sale),
                    Format.plain(sale.area()), Format.area(sale.area()), Format.plain(sale.price()), Format.euroWhole(sale.price()),
                    Format.plain(sale.perSquareMeter()), Format.perSquareMeter(sale.perSquareMeter()),
                    distances ? Html.of("<td class=\"num\" data-sort=\"%s\">%s</td>", sale.distance() == null ? "" : sale.distance(), distance(sale.distance())) : Html.EMPTY,
                    dvfMap(sale.latitude(), sale.longitude(), 18))).collect(Html.joining());
            Html table = Components.table(distances
                    ? java.util.List.of("Date", "Adresse", "#Surface", "#Prix", "#Prix au m²", "#Distance", "")
                    : java.util.List.of("Date", "Adresse", "#Surface", "#Prix", "#Prix au m²", ""), rows, true, true);
            String adjustment = price.indexMonth() == null ? "Prix au m² non actualisés."
                    : "Prix au m² actualisés à " + Format.month(price.indexMonth()).toLowerCase() + " selon l'évolution des prix du département ; prix de vente d'origine.";
            Html source = Html.of("<p class=\"source\">Ventes de surface comparable %s (%s), %s, valeurs extrêmes écartées. %s<br>"
                            + "Médiane : %s, de %s à %s. Source : DVF (DGFiP, Etalab) sur data.gouv.fr · calculé le %s.</p>",
                    price.scope() == null ? "" : price.scope().label(), price.place(), price.period(), adjustment,
                    Format.perSquareMeter(price.median()), Format.perSquareMeter(price.low()), Format.perSquareMeter(price.high()), Format.date(price.computedOn()));
            content = Html.concat(back, Components.heading("Prix au m²", "Ventes du secteur", property.name(), map),
                    Components.card("Ventes retenues", data.sales().size(), Html.EMPTY, Html.concat(table, source)));
        }
        return Layout.page("Ventes du secteur · " + property.name() + " · Patria", Layout.Section.REAL_ESTATE, csrf, content);
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
