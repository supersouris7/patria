package fr.patria.wizard;

import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;
import static fr.patria.wizard.WizardPages.*;

import fr.patria.budget.BudgetItem;
import fr.patria.common.Frequency;
import fr.patria.expense.ExpenseKind;
import fr.patria.household.Income;
import fr.patria.household.Shares;
import fr.patria.property.Property;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.List;
import java.util.Map;

/** Revue annuelle et rappels du Previsionnel (configuration en cours, revue conseillee). HTML uniquement. */
final class AnnualReviewPages {
    private AnnualReviewPages() {
    }

    /** Progression secondaire du Previsionnel ; le detail reste dans le guide. */
    static Html progress(int percent, int finished, int total, Step next, String csrf) {
        return Html.of("""
                <section class="setup-progress" aria-label="Configuration du foyer"><details><summary>Guide de saisie : %s %% des étapes faites</summary>\
                <p>%s étape%s sur %s faite%s ou passée%s. Prochaine : %s.</p>\
                <div class="setup-progress-actions"><a class="button secondary" href="/assistant">Continuer la configuration</a>\
                <form method="post" action="/assistant/encadre/ignorer">%s<button class="quiet" type="submit">Masquer cet encart</button></form></div></details></section>""".replace("%%", "%"),
                percent, finished, finished > 1 ? "s" : "", total, finished > 1 ? "s" : "", finished > 1 ? "s" : "", next.title(),
                fr.patria.ui.Components.csrfField(csrf));
    }

    static Html reminder(AnnualReview.CalendarMonth month) {
        return Html.of("""
                <section class="notice warning review-reminder"><div><b>Revue annuelle conseillée</b> : votre dernière mise à jour date de plus d'un an.
                <span>En %s : %s</span></div><a class="button secondary" href="/assistant/revue-annuelle">Faire la revue</a></section>""",
                month.month().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH), month.what());
    }

    static Html annual(WizardController.Context context, java.time.LocalDate reviewedOn, Notice notice) {
        Html calendar = AnnualReview.CALENDAR.stream().map(month -> Html.of("<tr%s><td>%s</td><td>%s</td></tr>",
                Html.raw(month.month() == context.today().getMonth() ? " class=\"highlight\"" : ""),
                capitalize(month.month().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.FRENCH)), month.what())).collect(Html.joining());
        List<Income> incomes = context.snapshot().incomes().stream().filter(income -> active(income.end(), context))
                .filter(income -> context.visibility().sees(income.personId())).toList();
        Html incomeRows = incomes.isEmpty() ? Html.raw("<p class=\"empty\">Aucun revenu en cours.</p>") : incomes.stream().map(income -> reviewRow(context,
                "/assistant/revue-annuelle/revenu/" + income.id(), income.label(), name(context.snapshot().people(), income.personId()) + " · depuis le " + Format.date(income.start()),
                income.amount(), income.frequency(), AnnualReview.nextOccurrence(income.start(), income.frequency(), context.today()))).collect(Html.joining());
        List<BudgetItem> items = context.snapshot().items().stream().filter(item -> item.frequency() != Frequency.ONCE && active(item.end(), context))
                .filter(item -> context.visibility().canEdit(context.shares().getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of())))
                .sorted(java.util.Comparator.comparing(BudgetItem::category).thenComparing(BudgetItem::label)).toList();
        Html itemRows = items.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense récurrente en cours.</p>") : items.stream().map(BudgetItem::category).distinct()
                .map(category -> Html.of("<h3 class=\"wizard-group\">%s</h3>%s", category.label(), items.stream().filter(item -> item.category() == category)
                        .map(item -> reviewRow(context, "/assistant/revue-annuelle/depense/" + item.id(), item.label(), "depuis le " + Format.date(item.start()),
                                item.amount(), item.frequency(), AnnualReview.nextOccurrence(item.start(), item.frequency(), context.today()))).collect(Html.joining())))
                .collect(Html.joining());
        Html properties = context.admin() && !context.snapshot().properties().isEmpty()
                ? Components.card("Biens", context.snapshot().properties().size(), Html.EMPTY, context.snapshot().properties().stream().map(property -> propertyReview(context, property))
                .collect(Html.joining())) : Html.EMPTY;
        Html finish = Html.of("""
                <form method="post" action="/assistant/revue-annuelle/terminer" class="wizard-footer">%s<span class="muted">%s</span><button type="submit">Revue terminée</button></form>""",
                Components.csrfField(context.csrf()), reviewedOn == null ? "Pas encore de revue enregistrée." : "Dernière revue terminée le " + Format.date(reviewedOn) + ".");
        Html content = Html.concat(
                Components.backLink("/assistant", "Guide de saisie"),
                Components.heading("Guide de saisie", "Revue annuelle", "Ce qui change d'une année sur l'autre, ligne par ligne, en quelques minutes.", Html.EMPTY),
                Components.notice(notice),
                explain("Pour chaque ligne : si le montant n'a pas changé, ne faites rien. S'il a changé, tapez le nouveau montant et la date à partir de laquelle il "
                        + "s'applique : Patria garde l'ancien jusqu'à la veille, l'historique reste juste. « Arrêtée » termine un abonnement résilié ou un revenu "
                        + "qui s'arrête. Une ligne nouvelle s'ajoute avec le guide de saisie."),
                Components.card("Revenus", incomes.size(), Html.EMPTY, Html.of("<div id=\"revenus\">%s</div>", incomeRows)),
                Components.card("Dépenses récurrentes", items.size(), Html.EMPTY, Html.of("<div id=\"depenses\">%s</div>", itemRows)),
                properties,
                Components.card("Calendrier des mises à jour", null, Html.EMPTY, Html.concat(
                        Html.raw("<p class=\"muted\">Ce qui change dans l'année, mois par mois : une bonne occasion de revenir ici.</p>"),
                        Html.of("<table class=\"data calendar\"><tbody>%s</tbody></table>", calendar))),
                finish);
        return Layout.page("Revue annuelle · Guide de saisie · Patria", Layout.Section.ASSISTANT, context.csrf(), content);
    }

    static boolean active(java.time.LocalDate end, WizardController.Context context) {
        return end == null || !end.isBefore(context.today());
    }

    static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Une ligne de la revue : montant actuel, nouveau montant, date d'effet, « Mettre à jour » ou « Arrêtée ». */
    static Html reviewRow(WizardController.Context context, String action, String label, String detail, java.math.BigDecimal amount, Frequency frequency,
                                  java.time.LocalDate from) {
        return Html.of("""
                <form method="post" action="%s" class="review-row">%s<div class="review-label"><b>%s</b><span class="help">%s</span></div>\
                <div class="review-current">%s<small>%s</small></div>\
                <div class="review-fields"><span class="suffix"><input name="amount" inputmode="decimal" data-money placeholder="%s" aria-label="Nouveau montant">€</span>\
                <input type="date" name="from" value="%s" aria-label="À partir du" title="À partir du">\
                <button class="secondary" type="submit" name="action" value="update">Mettre à jour</button>\
                <button class="quiet" type="submit" name="action" value="stop" title="Terminer à cette date">Arrêtée</button></div></form>""",
                action, Components.csrfField(context.csrf()), label, detail, euro(amount), frequency.label().toLowerCase(java.util.Locale.FRENCH),
                Format.plain(amount), from.toString());
    }

    static Html propertyReview(WizardController.Context context, Property property) {
        int year = context.today().getYear();
        java.util.Optional<fr.patria.expense.Expense> tax = context.snapshot().charges().stream()
                .filter(charge -> charge.propertyId() == property.id() && charge.kind() == ExpenseKind.PROPERTY_TAX && charge.year() == year).findFirst();
        Html taxPart = tax.isPresent()
                ? Html.of("<p class=\"review-done\"><span class=\"ok\" aria-hidden=\"true\">✓</span> Taxe foncière %s saisie : %s</p>", year, euroWhole(tax.get().amount()))
                : Html.of("""
                <form method="post" action="/assistant/revue-annuelle/taxe/%s" class="review-row">%s<div class="review-label"><b>Taxe foncière %s</b>\
                <span class="help">Montant de l'avis d'octobre ; sans saisie, Patria l'estime à partir de l'année précédente.</span></div><div></div>\
                <div class="review-fields"><span class="suffix"><input name="amount" inputmode="decimal" data-money placeholder="Montant" aria-label="Taxe foncière">€</span>\
                <button class="secondary" type="submit">Enregistrer</button></div></form>""", property.id(), Components.csrfField(context.csrf()), year);
        boolean old = property.estimateDate().plusYears(1).isBefore(context.today());
        Html estimatePart = !old
                ? Html.of("<p class=\"review-done\"><span class=\"ok\" aria-hidden=\"true\">✓</span> Estimation du %s : moins d'un an</p>", Format.date(property.estimateDate()))
                : Html.of("""
                <form method="post" action="/assistant/revue-annuelle/estimation/%s" class="review-row">%s<div class="review-label"><b>Estimation du bien</b>\
                <span class="help">Dernière estimation le %s : une nouvelle, même approximative (agence, site d'estimation), affine la valeur.</span></div><div></div>\
                <div class="review-fields"><span class="suffix"><input name="estimate" inputmode="decimal" data-money placeholder="Prudente" aria-label="Estimation prudente">€</span>\
                <span class="suffix"><input name="estimate_high" inputmode="decimal" data-money placeholder="Optimiste" aria-label="Estimation optimiste">€</span>\
                <button class="secondary" type="submit">Enregistrer</button></div></form>""", property.id(), Components.csrfField(context.csrf()), Format.date(property.estimateDate()));
        return Html.of("<section class=\"review-property\" id=\"biens\"><h3>%s</h3>%s%s</section>", property.name(), taxPart, estimatePart);
    }
}
