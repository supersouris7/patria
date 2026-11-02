package fr.patria.household;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;

import fr.patria.common.Frequency;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Administration du foyer : personnes et revenus. */
final class HouseholdPages {
    /** Frequences proposees pour un revenu. */
    static final List<Frequency> INCOME_FREQUENCIES = List.of(Frequency.MONTHLY, Frequency.ANNUAL, Frequency.ONCE);

    private HouseholdPages() {
    }

    static Map<String, String> values(Income income) {
        Map<String, String> values = new HashMap<>();
        values.put("person", String.valueOf(income.personId()));
        values.put("label", income.label());
        values.put("kind", income.kind().name());
        values.put("amount", Format.plain(income.amount()));
        values.put("frequency", income.frequency().name());
        values.put("start", income.start().toString());
        values.put("end", income.end() == null ? "" : income.end().toString());
        return values;
    }

    static Html incomeFields(List<Person> people, Map<String, String> values) {
        return Html.concat(Components.fields(3,
                        Components.select("Personne", "person", people.stream().map(person -> new Option(String.valueOf(person.id()), person.name())).toList(),
                                values.getOrDefault("person", people.isEmpty() ? "" : String.valueOf(people.getFirst().id()))),
                        Components.select("Type", "kind", Arrays.stream(IncomeKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                                values.getOrDefault("kind", IncomeKind.SALARY.name())),
                        Components.input(Input.of("Libellé", "label", Field.TEXT).required().maxLength(100).placeholder("Salaire"), values),
                        Components.input(Input.of("Montant net avant impôt (€)", "amount", Field.MONEY).required(), values),
                        Components.select("Fréquence", "frequency", INCOME_FREQUENCIES.stream().map(f -> new Option(f.name(), f.label())).toList(),
                                values.getOrDefault("frequency", Frequency.MONTHLY.name())),
                        Components.input(Input.of("Premier versement", "start", Field.DATE).required().help("Annuel : revient chaque année ce mois-là."), values),
                        Components.input(Input.of("Fin", "end", Field.DATE).help("Vide si en cours. Augmentation : terminer et ajouter le nouveau montant."), values)),
                Html.raw("<p class=\"muted\">Montant net avant impôt sur le revenu, comme sur la fiche de paie (« net à payer avant impôt »). "
                        + "Le prélèvement à la source se saisit dans les dépenses, catégorie « Impôts et taxes ».</p>"));
    }

    static Html page(List<Person> people, List<Income> incomes, Map<String, String> values, Notice notice, String csrf) {
        Html personRows = people.stream().map(person -> Html.of("<tr>%s%s<td class=\"actions\"><form method=\"post\" action=\"/personne/%s/supprimer\" data-confirm=\"%s\">%s"
                        + "<button class=\"quiet\" type=\"submit\">Supprimer</button></form></td></tr>",
                cell(person.name()), Html.of("<td class=\"num\">%s</td>", incomes.stream().filter(income -> income.personId() == person.id()).count()),
                person.id(), "Supprimer " + person.name() + " et ses revenus ?", Components.csrfField(csrf))).collect(Html.joining());
        Html peopleCard = Components.card("Personnes du foyer", people.size(), Html.EMPTY, Html.concat(
                people.isEmpty() ? Html.raw("<p class=\"empty\">Ajoutez au moins une personne pour saisir ses revenus.</p>")
                        : Components.table(List.of("Nom", "#Revenus", ""), personRows, false, false),
                Html.of("<form method=\"post\" action=\"/administration/foyer/personnes\" class=\"inline-form row-form\">%s%s<button type=\"submit\">Ajouter</button></form>",
                        Components.csrfField(csrf), Components.input(Input.of("Nouvelle personne", "name", Field.TEXT).required().maxLength(60).placeholder("Prénom"), Map.of()))));

        Html incomeRows = incomes.stream().map(income -> Html.of("<tr>%s%s%s%s%s%s<td class=\"actions\"><a href=\"/revenu/%s\">Modifier</a></td></tr>",
                cell(people.stream().filter(person -> person.id() == income.personId()).map(Person::name).findFirst().orElse(Format.DASH)),
                cell(income.label()), cell(income.kind().label()), number(income.amount(), Format.euro(income.amount())),
                cell(income.frequency().label()), cell(period(income)), income.id())).collect(Html.joining());
        Html incomesCard = Components.card("Revenus", incomes.size(), Html.EMPTY, incomes.isEmpty()
                ? Html.raw("<p class=\"empty\">Aucun revenu saisi.</p>")
                : Components.table(List.of("Personne", "Libellé", "Type", "#Montant", "Fréquence", "Période", ""), incomeRows, true, false));
        Html addIncome = people.isEmpty() ? Html.EMPTY : Components.card("Ajouter un revenu", null, Html.EMPTY,
                Components.form("/administration/foyer/revenus", csrf, false, incomeFields(people, values), "Ajouter le revenu"));

        Html content = Html.concat(Components.heading("Administration", "Foyer et revenus", null, Html.EMPTY), Components.notice(notice),
                peopleCard, addIncome, incomesCard);
        return Layout.adminSection("Foyer et revenus · Patria", csrf, Layout.AdminSection.HOUSEHOLD, content);
    }

    static Html edit(Income income, List<Person> people, Map<String, String> values, Notice notice, String csrf) {
        Html content = Html.concat(
                Components.backLink("/administration/foyer", "Foyer et revenus"),
                Components.heading("Revenu", income.label(), null, Html.EMPTY), Components.notice(notice),
                Components.card("Revenu", null, Html.EMPTY, Components.form("/revenu/" + income.id(), csrf, false, incomeFields(people, values), "Enregistrer")),
                Components.dangerZone("Supprimer ce revenu", "Il ne sera plus compté dans le budget.", "/revenu/" + income.id() + "/supprimer",
                        "Supprimer le revenu « " + income.label() + " » ?", "Supprimer le revenu", csrf));
        return Layout.adminSection("Revenu · Patria", csrf, Layout.AdminSection.HOUSEHOLD, content);
    }

    static String period(Income income) {
        if (income.frequency() == Frequency.ONCE) return Format.date(income.start());
        return "depuis " + Format.date(income.start()) + (income.end() == null ? "" : " jusqu'au " + Format.date(income.end()));
    }
}
