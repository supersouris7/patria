package fr.patria.wizard;

import static fr.patria.ui.Format.euro;
import static fr.patria.wizard.WizardPages.*;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.Category;
import fr.patria.budget.ExpenseCsv;
import fr.patria.common.Frequency;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Html;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Etapes du budget du guide : membres, revenus, logement, depenses fixes, impots, virements (listes a cocher). HTML uniquement. */
final class WizardBudgetPages {
    private WizardBudgetPages() {
    }

    static Html members(WizardController.Context context, Map<String, String> values) {
        List<Person> people = context.snapshot().people();
        Html list = people.isEmpty() ? Html.raw("<p class=\"empty\">Aucun membre pour l'instant.</p>")
                : Html.of("<ul class=\"done-list\">%s</ul>", people.stream().map(person -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s%s</li>",
                person.name(), person.isAccount() ? Html.raw(" <small class=\"badge\">compte joint</small>") : Html.EMPTY)).collect(Html.joining()));
        Html add = form(context, "/assistant/membres/ajouter", false, Components.fields(2,
                Components.input(Input.of("Nom", "name", Field.TEXT).required().maxLength(60).placeholder("Prénom, ou « Compte joint »"), values),
                Components.select("Type", "kind", List.of(new Option("PERSON", "Personne"), new Option("ACCOUNT", "Compte joint")), values.getOrDefault("kind", "PERSON"))),
                "Ajouter ce membre");
        return Html.concat(explain("Ajoutez chaque personne dont les revenus et les dépenses comptent dans le budget, puis le compte joint s'il y en a un : "
                        + "il a son propre budget, alimenté par des virements. Un membre n'est pas un compte de connexion : les comptes se créent dans "
                        + "Utilisateurs dans la navigation de l'administrateur."),
                Components.card("Membres du foyer", people.size(), Html.EMPTY, Html.concat(list, add)));
    }

    static Html incomes(WizardController.Context context, Map<String, String> values) {
        List<Person> open = context.visibility().people().stream().filter(person -> !person.isAccount()).toList();
        if (open.isEmpty()) {
            return Html.concat(explain("Les revenus se rattachent à une personne du foyer."),
                    Components.card("Revenus", null, Html.EMPTY, Html.raw(context.admin()
                            ? "<p class=\"empty\">Aucune personne dans le foyer : ajoutez-en à l'étape Membres.</p>"
                            : "<p class=\"empty\">Aucune personne ne vous est ouverte (seulement un compte joint, ou aucune vue) : passez cette étape, "
                            + "ou demandez à l'administrateur de vous ouvrir votre vue.</p>")));
        }
        Html cards = open.stream().map(person -> {
            List<Income> incomes = context.snapshot().incomes().stream().filter(income -> income.personId() == person.id()).toList();
            Html list = incomes.isEmpty() ? Html.raw("<p class=\"muted\">Aucun revenu saisi.</p>")
                    : Html.of("<ul class=\"done-list\">%s</ul>", incomes.stream().map(income -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s · %s · %s</li>",
                    income.label(), euro(income.amount()), income.frequency().label().toLowerCase(java.util.Locale.FRENCH))).collect(Html.joining()));
            Map<String, String> own = values.getOrDefault("person", "").equals(String.valueOf(person.id())) ? values : Map.of();
            Html add = form(context, "/assistant/revenus/ajouter", false, Html.concat(Html.of("<input type=\"hidden\" name=\"person\" value=\"%s\">", person.id()),
                    Components.fields(3,
                            Components.select("Type", "kind", Arrays.stream(IncomeKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                                    own.getOrDefault("kind", IncomeKind.SALARY.name())),
                            Components.input(Input.of("Libellé", "label", Field.TEXT).required().maxLength(100).placeholder("Salaire, pension, allocations…"), own),
                            Components.input(Input.of("Montant (€)", "amount", Field.MONEY).required().help("Net avant impôt sur le revenu."), own),
                            Components.select("Fréquence", "frequency", List.of(new Option("MONTHLY", "Mensuel"), new Option("ANNUAL", "Annuel (prime, 13e mois)"),
                                    new Option("ONCE", "Ponctuel")), own.getOrDefault("frequency", "MONTHLY")),
                            Components.input(Input.of("Premier versement", "start", Field.DATE).required().help("Annuel : le mois où il est versé."),
                                    own.isEmpty() ? Map.of("start", context.today().withDayOfMonth(1).toString()) : own))),
                    "Ajouter ce revenu");
            return Html.of("<section class=\"card section\" id=\"revenus-%s\"><div class=\"section-title\"><h2>%s</h2></div>%s%s</section>", person.id(), person.name(), list, add);
        }).collect(Html.joining());
        return Html.concat(explain("Pour chaque personne : le salaire net avant impôt (ligne « Net à payer avant impôt sur le revenu » du bulletin), "
                + "les primes ou 13e mois en annuel, les pensions et allocations. Un revenu qui varie : indiquez une moyenne mensuelle. Les loyers perçus "
                + "se saisissent avec le bien, à l'étape Charges et loyers."), cards);
    }

    static Html checklist(WizardController.Context context, String action, List<Checklist.Group> groups, boolean custom, Map<String, String> values, String submit) {
        Html blocks = groups.stream().map(group -> {
            long existing = group.items().stream().filter(item -> context.snapshot().item(item.label()).isPresent()).count();
            Html rows = group.items().stream().map(item -> checkRow(context, item, values)).collect(Html.joining());
            return Html.of("<details class=\"check-group\" open><summary><b>%s</b>%s</summary><p class=\"muted\">%s</p><div class=\"check-rows\">%s</div></details>",
                    group.title(), existing == 0 ? Html.EMPTY : Html.of(" <span class=\"badge paid\">%s déjà saisie%s</span>", existing, existing > 1 ? "s" : ""),
                    group.explanation(), rows);
        }).collect(Html.joining());
        Html extra = Html.EMPTY;
        if (custom) {
            StringBuilder rows = new StringBuilder();
            for (int row = 1; row <= 3; row++) rows.append(customRow(context, "custom" + row, values));
            extra = Html.of("<details class=\"check-group\" open><summary><b>Autres dépenses</b></summary><p class=\"muted\">Ce qui ne figure pas dans les listes.</p>"
                    + "<div class=\"check-rows\">%s</div></details>", Html.raw(rows.toString()));
        }
        return form(context, action, false, Html.concat(blocks, extra), submit);
    }

    static Html checkRow(WizardController.Context context, Checklist.Item item, Map<String, String> values) {
        java.util.Optional<BudgetItem> existing = context.snapshot().item(item.label());
        if (item.repeatable()) {
            // Ligne saisie une fois par personne : les saisies existantes, puis de quoi en ajouter une autre.
            Html saved = context.snapshot().itemsNamed(item.label()).stream().map(entry -> Html.of(
                    "<span class=\"help\"><span class=\"ok\" aria-hidden=\"true\">✓</span> déjà saisie : %s · %s%s · <a href=\"/depense/%s\">Modifier</a></span>",
                    euro(entry.amount()), entry.frequency().label().toLowerCase(java.util.Locale.FRENCH), owner(context, entry), entry.id())).collect(Html.joining());
            return Html.of("<div class=\"check-row\"><label class=\"check-label\"><input type=\"checkbox\" name=\"on_%s\" value=\"on\"%s data-check-row><span><b>%s</b>%s%s</span></label>%s</div>",
                    item.id(), "on".equals(values.get("on_" + item.id())) ? Html.raw(" checked") : Html.EMPTY, item.label(),
                    item.hint() == null ? Html.EMPTY : Html.of("<span class=\"help\">%s</span>", item.hint()), saved,
                    rowFields(context, item.id(), item.frequency(), values));
        }
        if (existing.isPresent()) {
            BudgetItem saved = existing.get();
            return Html.of("<div class=\"check-row saved\"><span class=\"ok\" aria-hidden=\"true\">✓</span><span class=\"check-label\"><b>%s</b>"
                            + "<span class=\"help\">Déjà saisie : %s · %s</span></span><a href=\"/depense/%s\">Modifier</a></div>",
                    item.label(), euro(saved.amount()), saved.frequency().label().toLowerCase(java.util.Locale.FRENCH), saved.id());
        }
        return Html.of("<div class=\"check-row\"><label class=\"check-label\"><input type=\"checkbox\" name=\"on_%s\" value=\"on\"%s data-check-row><span><b>%s</b>%s</span></label>%s</div>",
                item.id(), "on".equals(values.get("on_" + item.id())) ? Html.raw(" checked") : Html.EMPTY, item.label(),
                item.hint() == null ? Html.EMPTY : Html.of("<span class=\"help\">%s</span>", item.hint()),
                rowFields(context, item.id(), item.frequency(), values));
    }

    /** « · Paul » : a qui revient une depense deja saisie, s'il y a plusieurs membres. */
    static String owner(WizardController.Context context, BudgetItem item) {
        List<Person> people = context.snapshot().people();
        if (people.size() < 2) return "";
        String text = Shares.describe(context.shares().getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of()), people);
        return text.isEmpty() ? "" : " · " + text;
    }

    static Html customRow(WizardController.Context context, String id, Map<String, String> values) {
        return Html.of("<div class=\"check-row custom\"><div class=\"check-label\"><input name=\"label_%s\" value=\"%s\" maxlength=\"100\" placeholder=\"Libellé\" aria-label=\"Libellé\">"
                        + "<select name=\"category_%s\" aria-label=\"Catégorie\">%s</select></div>%s</div>",
                id, values.getOrDefault("label_" + id, ""), id, Category.selectable().stream().map(category -> Html.of("<option value=\"%s\"%s>%s</option>", category.name(),
                        category.name().equals(values.getOrDefault("category_" + id, Category.OTHER.name())) ? Html.raw(" selected") : Html.EMPTY, category.label())).collect(Html.joining()),
                rowFields(context, id, Frequency.MONTHLY, values));
    }

    /** Montant, frequence, premier paiement et attribution d'une ligne. */
    static Html rowFields(WizardController.Context context, String id, Frequency frequency, Map<String, String> values) {
        Html frequencies = Arrays.stream(Frequency.values()).map(option -> Html.of("<option value=\"%s\"%s>%s</option>", option.name(),
                option.name().equals(values.getOrDefault("frequency_" + id, frequency.name())) ? Html.raw(" selected") : Html.EMPTY, option.label())).collect(Html.joining());
        List<Person> people = context.visibility().people();
        Html who = people.size() < 2 ? Html.EMPTY : Html.of("<select name=\"who_%s\" aria-label=\"Pour qui\"><option value=\"\">%s</option>%s</select>", id,
                context.admin() ? "Parts égales" : "Mes membres, parts égales",
                people.stream().map(person -> Html.of("<option value=\"%s\"%s>%s</option>", person.id(),
                        String.valueOf(person.id()).equals(values.get("who_" + id)) ? Html.raw(" selected") : Html.EMPTY, person.name())).collect(Html.joining()));
        return Html.of("<div class=\"check-fields\"><span class=\"suffix\"><input name=\"amount_%s\" value=\"%s\" inputmode=\"decimal\" data-money placeholder=\"Montant\" aria-label=\"Montant\">€</span>"
                        + "<select name=\"frequency_%s\" aria-label=\"Fréquence\">%s</select><input type=\"date\" name=\"start_%s\" value=\"%s\" aria-label=\"Premier paiement\" title=\"Premier paiement\">%s</div>",
                id, values.getOrDefault("amount_" + id, ""), id, frequencies, id, values.getOrDefault("start_" + id, context.today().withDayOfMonth(1).toString()), who);
    }

    static Html housing(WizardController.Context context, Map<String, String> values) {
        Html owner = Html.of("<section class=\"card section\"><div class=\"section-title\"><h2>Si vous êtes propriétaire</h2></div><p class=\"muted\">%s</p></section>",
                context.admin()
                        ? "Votre logement se saisit comme un bien, aux étapes suivantes : sa valeur, son prêt (tableau d'amortissement), sa taxe foncière, sa copropriété, "
                        + "son assurance. Patria les compte automatiquement dans le budget : ne les saisissez pas ici."
                        : "Les biens et leurs prêts sont saisis par le gestionnaire du foyer ; leurs mensualités et charges apparaissent dans votre budget pour votre part.");
        return Html.concat(explain("Le logement est la plus grosse dépense fixe. Locataire : le loyer et les charges locatives reviennent chaque mois. "
                        + "Propriétaire : le prêt et les charges du bien sont repris de l'immobilier. Les deux à la fois (locataire et propriétaire d'un logement loué) : "
                        + "remplissez les deux parties."),
                Components.card(Checklist.RENT.title(), null, Html.EMPTY, checklist(context, "/assistant/logement/ajouter", List.of(Checklist.RENT), false, values,
                        "Ajouter ces dépenses")),
                owner);
    }

    static Html fixed(WizardController.Context context, Map<String, String> values) {
        Html quick = Html.of("""
                <details class="help-block"><summary>Plus rapide : tout importer avec vos relevés bancaires</summary>
                <p class="muted">L'IA est facultative. Si vous l'utilisez, caviardez et vérifiez d'abord les copies de vos relevés et contrats. Joignez uniquement ces copies avec le prompt : elle renvoie un fichier
                à importer dans <a href="/administration/depenses">Gérer → Dépenses → Importer</a>. Revenez ensuite ici : les lignes importées apparaîtront cochées.</p>%s</details>""",
                Components.llmPrompt("prompt-depenses-assistant", ExpenseCsv.llmPrompt()));
        return Html.concat(explain("Une dépense fixe revient au même rythme et presque au même montant : abonnements, énergie, assurances, garde d'enfants, "
                        + "transports. Une dépense variable (courses, loisirs) se saisit avec un budget moyen. Cochez ce que vous payez, indiquez le montant "
                        + "d'un paiement et sa fréquence : Patria calcule les 12 prochains mois. Inutile de saisir ce que Patria reprend de l'immobilier "
                        + "(mensualités des prêts immobiliers, taxe foncière, copropriété, assurance d'un bien possédé)."),
                Components.card("Dépenses fixes", null, Html.EMPTY, Html.concat(quick,
                        checklist(context, "/assistant/depenses/ajouter", Checklist.FIXED, true, values, "Ajouter les dépenses cochées"))));
    }

    static Html taxes(WizardController.Context context, Map<String, String> values) {
        return Html.concat(explain("Sur l'avis d'impôt (impots.gouv.fr → Documents), le cadre « Votre prélèvement à la source » donne le taux et le montant "
                        + "mensuel. Avec deux salaires, saisissez un prélèvement par personne (« Pour qui »). Un solde à payer se saisit en ponctuel à sa date."),
                Components.card(Checklist.TAXES.title(), null, Html.EMPTY, checklist(context, "/assistant/impots/ajouter", List.of(Checklist.TAXES), false, values,
                        "Ajouter ces impôts")));
    }

    static Html transfers(WizardController.Context context, Map<String, String> values) {
        List<Person> people = context.visibility().people();
        List<Person> everyone = context.snapshot().people();
        Html list = context.snapshot().transfers().isEmpty() ? Html.raw("<p class=\"muted\">Aucun virement saisi.</p>")
                : Html.of("<ul class=\"done-list\">%s</ul>", context.snapshot().transfers().stream().map(transfer -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s → %s · %s · %s</li>",
                name(everyone, transfer.fromId()), name(everyone, transfer.toId()), euro(transfer.amount()), transfer.frequency().label().toLowerCase(java.util.Locale.FRENCH)))
                .collect(Html.joining()));
        if (people.size() < 2) {
            return Html.concat(explain("Un virement va d'un membre à un autre, par exemple de chaque personne vers le compte joint."),
                    Components.card("Virements", null, Html.EMPTY, Html.concat(list, Html.raw("<p class=\"empty\">Il faut au moins deux membres (une personne et un compte joint) : passez cette étape s'il n'y a pas de compte joint.</p>"))));
        }
        List<Option> members = people.stream().map(person -> new Option(String.valueOf(person.id()), person.name())).toList();
        String from = values.getOrDefault("from", people.stream().filter(person -> !person.isAccount()).map(person -> String.valueOf(person.id())).findFirst().orElse(""));
        String to = values.getOrDefault("to", people.stream().filter(Person::isAccount).map(person -> String.valueOf(person.id())).findFirst().orElse(""));
        Html add = form(context, "/assistant/virements/ajouter", false, Components.fields(3,
                Components.select("De", "from", members, from), Components.select("Vers", "to", members, to),
                Components.input(Input.of("Montant (€)", "amount", Field.MONEY).required(), values),
                Components.select("Fréquence", "frequency", List.of(new Option("MONTHLY", "Mensuel"), new Option("ONCE", "Ponctuel")), values.getOrDefault("frequency", "MONTHLY")),
                Components.input(Input.of("Premier virement", "start", Field.DATE).required(), values.isEmpty() ? Map.of("start", context.today().withDayOfMonth(1).toString()) : values),
                Components.input(Input.of("Libellé", "label", Field.TEXT).maxLength(100).placeholder("Participation aux dépenses communes"), values)), "Ajouter ce virement");
        return Html.concat(explain("Avec un compte joint, chacun y verse une somme fixe chaque mois, et le compte paie les dépenses communes qu'on lui attribue "
                + "(« Pour qui » dans les dépenses). Les virements apparaissent dans le budget de chacun : sortie pour l'un, entrée pour le compte joint."),
                Components.card("Virements", context.snapshot().transfers().size(), Html.EMPTY, Html.concat(list, add)));
    }
}
