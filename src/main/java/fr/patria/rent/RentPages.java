package fr.patria.rent;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.sortedCell;

import fr.patria.property.Property;
import fr.patria.property.PropertyNav;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Administration des loyers d'un bien. */
final class RentPages {
    private RentPages() {
    }

    static Map<String, String> values(RentPeriod period) {
        Map<String, String> values = new HashMap<>();
        values.put("label", period.label());
        values.put("start", period.start().toString());
        values.put("end", period.end() == null ? "" : period.end().toString());
        values.put("monthly", Format.plain(period.monthly()));
        return values;
    }

    private static Html fields(Map<String, String> values) {
        return Html.concat(Components.fields(2,
                        Components.input(Input.of("Loyer mensuel hors charges (€)", "monthly", Field.MONEY).required(), values),
                        Components.input(Input.of("Locataire ou commentaire", "label", Field.TEXT).maxLength(100), values),
                        Components.input(Input.of("Début", "start", Field.DATE).required(), values),
                        Components.input(Input.of("Fin", "end", Field.DATE).help("Vide si le bail est en cours."), values)),
                Html.raw("<p class=\"muted\">Une période par loyer : en cas de révision ou de changement de locataire, terminez la période et "
                        + "ajoutez-en une nouvelle. Les mois sans période comptent comme vacants.</p>"));
    }

    static Html page(Property property, List<RentPeriod> periods, LocalDate today, Map<String, String> values, Notice notice, String csrf) {
        Html rows = periods.stream().map(period -> Html.of("<tr>%s%s%s%s%s</tr>",
                sortedCell(period.start(), Format.date(period.start())),
                cell(period.end() == null ? Html.raw("<span class=\"badge paid\">En cours</span>") : Html.text(Format.date(period.end()))),
                number(period.monthly(), Format.euro(period.monthly()) + "/mois"),
                cell(period.label().isBlank() ? Format.DASH : period.label()),
                Html.of("<td class=\"actions\"><a href=\"/loyer/%s\">Modifier</a></td>", period.id()))).collect(Html.joining());
        Html table = periods.isEmpty() ? Html.raw("<p class=\"empty\">Aucune location enregistrée : le bien est considéré comme non loué.</p>")
                : Html.concat(Components.table(List.of("Début", "Fin", "#Loyer", "Locataire", ""), rows, false, false),
                        Html.of("<p class=\"source\">Loyers perçus depuis le début : <strong>%s</strong> · loyer actuel : <strong>%s</strong></p>",
                                Format.euro(RentMath.receivedToDate(periods, today)), Format.euro(RentMath.currentMonthly(periods, today)) + "/mois"));
        Html content = Html.concat(
                Components.heading("Bien", "Loyers", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Ajouter une période de location", null, Html.EMPTY,
                        Components.form("/administration/bien/" + property.id() + "/loyers", csrf, false, fields(values), "Ajouter")),
                Components.card("Périodes de location", periods.size(), Html.EMPTY, table));
        return Layout.adminPage("Loyers · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.RENTS), content);
    }

    static Html edit(Property property, RentPeriod period, Map<String, String> values, Notice notice, String csrf) {
        Html content = Html.concat(
                Components.backLink("/administration/bien/" + property.id() + "/loyers", "Loyers"),
                Components.heading("Loyer", "Modifier la période", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Période", null, Html.EMPTY, Components.form("/loyer/" + period.id(), csrf, false, fields(values), "Enregistrer")),
                Components.dangerZone("Supprimer cette période", "Les loyers de cette période ne seront plus comptés.", "/loyer/" + period.id() + "/supprimer",
                        "Supprimer cette période de location ?", "Supprimer la période", csrf));
        return Layout.page("Loyer · " + property.name() + " · Patria", Layout.Section.ADMINISTRATION, csrf, content);
    }
}
