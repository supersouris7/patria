package fr.patria.rent;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.sortedCell;

import fr.patria.property.Property;
import fr.patria.property.PropertyNav;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import java.util.Arrays;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Administration de l'occupation d'un bien : locations et residence principale. */
final class RentPages {
    private RentPages() {
    }

    static Map<String, String> values(RentPeriod period) {
        Map<String, String> values = new HashMap<>();
        values.put("kind", period.kind().name());
        values.put("label", period.label());
        values.put("start", period.start().toString());
        values.put("end", period.end() == null ? "" : period.end().toString());
        values.put("monthly", Format.plain(period.monthly()));
        return values;
    }

    private static Html fields(Map<String, String> values) {
        return Html.concat(Components.fields(2,
                        Components.select("Usage", "kind", Arrays.stream(Occupancy.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                                values.getOrDefault("kind", Occupancy.RENTAL.name())),
                        Components.input(Input.of("Loyer mensuel hors charges (€)", "monthly", Field.MONEY).required()
                                .help("Location : loyer perçu. Résidence principale : loyer d'un logement équivalent."), values),
                        Components.input(Input.of("Début", "start", Field.DATE).required(), values),
                        Components.input(Input.of("Fin", "end", Field.DATE).help("Vide si la période est en cours."), values),
                        Components.input(Input.of("Locataire ou commentaire", "label", Field.TEXT).maxLength(100), values)),
                Html.raw("<p class=\"muted\">Résidence principale : le loyer de référence est ce que vous paieriez pour vous loger ailleurs. "
                        + "Il compte comme loyer économisé dans l'enrichissement réel. Une période par montant : pour une révision ou un changement d'usage, "
                        + "terminez la période et ajoutez-en une nouvelle. Les jours sans période comptent comme vacants.</p>"));
    }

    static Html page(Property property, List<RentPeriod> periods, LocalDate today, Map<String, String> values, Notice notice, String csrf) {
        Html rows = periods.stream().map(period -> Html.of("<tr>%s%s%s%s%s%s</tr>",
                cell(period.kind().label()),
                sortedCell(period.start(), Format.date(period.start())),
                cell(period.end() == null ? Html.raw("<span class=\"badge paid\">En cours</span>") : Html.text(Format.date(period.end()))),
                number(period.monthly(), Format.euro(period.monthly()) + "/mois"),
                cell(period.label().isBlank() ? Format.DASH : period.label()),
                Html.of("<td class=\"actions\"><a href=\"/loyer/%s\">Modifier</a></td>", period.id()))).collect(Html.joining());
        Html table = periods.isEmpty() ? Html.raw("<p class=\"empty\">Aucune période : le bien est considéré comme vacant.</p>")
                : Html.concat(Components.table(List.of("Usage", "Début", "Fin", "#Loyer", "Commentaire", ""), rows, false, false),
                        Html.of("<p class=\"source\">Loyers perçus : <strong>%s</strong> · loyers économisés (résidence principale) : <strong>%s</strong></p>",
                                Format.euro(RentMath.receivedToDate(periods, today)), Format.euro(RentMath.savedToDate(periods, today))));
        Html content = Html.concat(
                Components.heading("Bien", "Occupation", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Ajouter une période", null, Html.EMPTY,
                        Components.form("/administration/bien/" + property.id() + "/loyers", csrf, false, fields(values), "Ajouter")),
                Components.card("Périodes d'occupation", periods.size(), Html.EMPTY, table));
        return Layout.adminPage("Occupation · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.RENTS), content);
    }

    static Html edit(Property property, RentPeriod period, Map<String, String> values, Notice notice, String csrf) {
        Html content = Html.concat(
                Components.backLink("/administration/bien/" + property.id() + "/loyers", "Occupation"),
                Components.heading("Occupation", "Modifier la période", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Période", null, Html.EMPTY, Components.form("/loyer/" + period.id(), csrf, false, fields(values), "Enregistrer")),
                Components.dangerZone("Supprimer cette période", "Les loyers de cette période ne seront plus comptés.", "/loyer/" + period.id() + "/supprimer",
                        "Supprimer cette période ?", "Supprimer la période", csrf));
        return Layout.page("Occupation · " + property.name() + " · Patria", Layout.Section.ADMINISTRATION, csrf, content);
    }
}
