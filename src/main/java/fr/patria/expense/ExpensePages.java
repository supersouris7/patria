package fr.patria.expense;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;

import fr.patria.property.Property;
import fr.patria.property.PropertyNav;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Administration des charges d'un bien. */
final class ExpensePages {
    private ExpensePages() {
    }

    static Html page(Property property, List<Expense> entered, boolean monthlyTax, LocalDate today, Map<String, String> values, Notice notice, String csrf) {
        Html form = Components.form("/administration/bien/" + property.id() + "/charges", csrf, false, Html.concat(
                Components.fields(3,
                        Components.select("Type", "kind", Arrays.stream(ExpenseKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                                values.getOrDefault("kind", ExpenseKind.PROPERTY_TAX.name())),
                        Components.input(Input.of("Année", "year", Field.NUMBER).required().placeholder(String.valueOf(today.getYear())), values),
                        Components.input(Input.of("Montant annuel (€)", "amount", Field.MONEY).required(), values)),
                Html.raw("<p class=\"muted\">Une seule saisie par type suffit : les autres années sont estimées (taxe foncière revalorisée chaque année "
                        + "selon le coefficient légal, autres charges constantes). Saisir à nouveau une année remplace son montant.</p>")), "Enregistrer");

        List<ExpenseMath.YearAmount> years = ExpenseMath.years(entered, property.purchaseDate(), today);
        Html rows = years.stream().map(year -> {
            Expense source = entered.stream().filter(e -> e.kind() == year.kind() && e.year() == year.year()).findFirst().orElse(null);
            Html action = source == null ? Html.raw("<td></td>") : Html.of(
                    "<td class=\"actions\"><form method=\"post\" action=\"/charge/%s/supprimer\" data-confirm=\"Supprimer ce montant saisi ?\">%s<button class=\"quiet\" type=\"submit\">Supprimer</button></form></td>",
                    source.id(), Components.csrfField(csrf));
            return Html.of("<tr>%s%s%s%s%s</tr>", cell(year.year()), cell(year.kind().label()), number(year.amount(), Format.euro(year.amount())),
                    cell(year.estimated() ? Html.of("<span class=\"badge\" title=\"%s\">Estimée</span>", year.kind().estimation())
                            : Html.raw("<span class=\"badge paid\">Saisie</span>")), action);
        }).collect(Html.joining());
        BigDecimal paid = ExpenseMath.paidToDate(entered, property.purchaseDate(), today);
        Html table = years.isEmpty() ? Html.raw("<p class=\"empty\">Aucune charge saisie.</p>") : Html.concat(
                Components.table(List.of("Année", "Type", "#Montant annuel", "Origine", ""), rows, false, false),
                Html.of("<p class=\"source\">Total payé depuis l'achat (le %s), au prorata de la première et de l'année en cours : <strong>%s</strong></p>",
                        Format.date(property.purchaseDate()), Format.euro(paid)));

        Html payment = Components.form("/administration/bien/" + property.id() + "/charges/paiement", csrf, false, Html.concat(
                Components.fields(2, Components.select("Prélèvement", "payment", List.of(
                        new Option("ONCE", "En une fois, en octobre"),
                        new Option("MONTHLY", "Mensualisée, de janvier à octobre")), monthlyTax ? "MONTHLY" : "ONCE")),
                Html.raw("<p class=\"muted\">Change seulement les mois du budget où la taxe est payée ; le montant annuel reste celui saisi ci-dessus. "
                        + "Mensualisée : 10 prélèvements, chacun un dixième du montant de l'année (échéancier des impôts).</p>")), "Enregistrer");

        Html content = Html.concat(
                Components.heading("Bien", "Charges", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Saisir une charge", null, Html.EMPTY, form),
                Components.card("Paiement de la taxe foncière", null, Html.EMPTY, payment),
                Components.card("Charges depuis l'achat", null, Html.EMPTY, table));
        return Layout.adminPage("Charges · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.EXPENSES), content);
    }
}
