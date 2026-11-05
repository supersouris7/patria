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

    /**
     * Membres du foyer affiche (administrateur) : personnes et comptes joints, avec les comptes de connexion lies a
     * chacun ({@code logins} : identifiants par membre).
     */
    static Html members(List<Person> people, List<Income> incomes, Map<Long, List<String>> logins, long householdId, Notice notice, String csrf) {
        Html rows = people.stream().map(person -> {
            List<String> linked = logins.getOrDefault(person.id(), List.of());
            Html account = !linked.isEmpty() ? Html.text(String.join(", ", linked))
                    : fr.patria.ui.Layout.canOpen("/administration/utilisateurs")
                    ? Html.of("<a href=\"/administration/utilisateurs?membre=%s:%s\">Créer un compte</a>", householdId, person.id())
                    : Html.raw("<span class=\"muted\">Aucun</span>");
            return Html.of("<tr>%s%s%s<td>%s</td><td class=\"actions\"><form method=\"post\" action=\"/personne/%s/supprimer\" data-confirm=\"%s\">%s"
                            + "<button class=\"quiet\" type=\"submit\">Supprimer</button></form></td></tr>",
                    cell(person.name()), cell(person.isAccount() ? Html.raw("<small class=\"badge\">Compte joint</small>") : Html.text("Personne")),
                    Html.of("<td class=\"num\">%s</td>", incomes.stream().filter(income -> income.personId() == person.id()).count()), account,
                    person.id(), "Supprimer " + person.name() + ", ses revenus et ses virements ?" + (people.size() > 1
                            ? " Ses parts des dépenses, biens et prêts seront réparties entre les autres membres." : ""), Components.csrfField(csrf));
        }).collect(Html.joining());
        Html list = people.isEmpty() ? Html.raw("<p class=\"empty\">Aucun membre : ajoutez les personnes du foyer, puis un compte joint s'il y en a un.</p>")
                : Components.table(List.of("Nom", "Type", "#Revenus", "Compte de connexion", ""), rows, false, false);
        Html form = Html.of("<form method=\"post\" action=\"/administration/membres\" class=\"inline-form row-form\">%s%s%s<button type=\"submit\">Ajouter</button></form>",
                Components.csrfField(csrf), Components.input(Input.of("Nouveau membre", "name", Field.TEXT).required().maxLength(60).placeholder("Prénom, ou « Compte joint »"), Map.of()),
                Components.select("Type", "kind", Arrays.stream(Person.Kind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(), Person.Kind.PERSON.name()));
        Html help = Html.raw("""
                <p class="muted">Un <b>membre</b> est une personne du budget (ou un compte joint) : il a des revenus et une part des dépenses. Ce n'est pas
                un compte de connexion. Pour qu'une personne consulte Patria, l'administrateur lui crée un <b>utilisateur</b> lié à son membre.
                Un compte joint a son propre budget : il reçoit des virements et paie les dépenses qu'on lui attribue ; il ne prend part aux
                « parts égales » que si on lui donne un pourcentage.</p>""");
        Html content = Html.concat(Components.heading("Administration", "Membres", null, Html.EMPTY), Components.notice(notice),
                Components.card("Membres du foyer", people.size(), Html.EMPTY, Html.concat(list, form, help)));
        return Layout.adminSection("Membres · Patria", csrf, Layout.AdminSection.MEMBERS, content);
    }

    /** Revenus visibles par la personne connectee ; un utilisateur ne voit que ceux de ses vues ouvertes. */
    static Html incomes(Visibility visibility, List<Income> allIncomes, Map<String, String> values, Notice notice, String csrf) {
        List<Person> people = visibility.everyone();
        List<Person> formPeople = visibility.people();
        List<Income> incomes = allIncomes.stream().filter(income -> visibility.canSeeAny(income.personId())).toList();
        Html incomeRows = incomes.stream().map(income -> Html.of("<tr>%s%s%s%s%s%s<td class=\"actions\">%s</td></tr>",
                cell(people.stream().filter(person -> person.id() == income.personId()).map(Person::name).findFirst().orElse(Format.DASH)),
                cell(income.label()), cell(income.kind().label()), number(income.amount(), Format.euro(income.amount())),
                cell(income.frequency().label()), cell(period(income)),
                visibility.sees(income.personId()) ? Html.of("<a href=\"/revenu/%s\">Modifier</a>", income.id())
                        : Html.of("<a href=\"/revenu/%s\">Voir</a>", income.id()))).collect(Html.joining());
        Html incomesCard = Components.card("Revenus", incomes.size(), Html.EMPTY, incomes.isEmpty()
                ? Html.raw("<p class=\"empty\">Aucun revenu saisi.</p>")
                : Components.table(List.of("Personne", "Libellé", "Type", "#Montant", "Fréquence", "Période", ""), incomeRows, true, false));
        Html addIncome = formPeople.isEmpty()
                ? (visibility.manages() ? Components.card("Ajouter un revenu", null, Html.EMPTY, Html.raw(
                        "<p class=\"empty\">Ajoutez d'abord les membres du foyer : <a href=\"/administration/membres\">Membres</a>.</p>")) : Html.EMPTY)
                : Components.card("Ajouter un revenu", null, Html.EMPTY,
                        Components.form("/administration/revenus", csrf, false, incomeFields(formPeople, values), "Ajouter le revenu"));
        Html content = Html.concat(Components.heading("Administration", "Revenus", null, Html.EMPTY), Components.notice(notice), addIncome, incomesCard);
        return Layout.adminSection("Revenus · Patria", csrf, Layout.AdminSection.INCOMES, content);
    }

    /** {@code back} : page d'ou l'on vient (le budget), {@code null} pour revenir aux revenus. */
    static Html edit(Income income, List<Person> people, Map<String, String> values, Notice notice, String back, boolean readOnly, String csrf) {
        String suffix = back == null ? "" : "?retour=" + java.net.URLEncoder.encode(back, java.nio.charset.StandardCharsets.UTF_8);
        Html content = Html.concat(
                back == null ? Components.backLink("/administration/revenus", "Revenus") : Components.backLink(back, "Budget moyen"),
                Components.heading("Revenu", income.label(), null, Html.EMPTY), Components.notice(notice),
                Components.card("Revenu", null, Html.EMPTY, Components.formOrView(readOnly, "/revenu/" + income.id() + suffix, csrf, incomeFields(people, values), "Enregistrer")),
                readOnly ? Html.EMPTY : Components.dangerZone("Supprimer ce revenu", "Il ne sera plus compté dans le budget.", "/revenu/" + income.id() + "/supprimer" + suffix,
                        "Supprimer le revenu « " + income.label() + " » ?", "Supprimer le revenu", csrf));
        return Layout.adminSection("Revenu · " + income.label() + " · Patria", csrf, Layout.AdminSection.INCOMES, content);
    }

    static String period(Income income) {
        if (income.frequency() == Frequency.ONCE) return Format.date(income.start());
        return "depuis " + Format.date(income.start()) + (income.end() == null ? "" : " jusqu'au " + Format.date(income.end()));
    }
}
