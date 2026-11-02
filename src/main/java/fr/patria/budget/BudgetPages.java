package fr.patria.budget;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.tile;
import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;

import fr.patria.common.Frequency;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Pages du budget : accueil (mois en cours et 12 mois a venir), budget moyen, saisie des depenses. */
final class BudgetPages {
    private BudgetPages() {
    }

    // ---- Accueil ----

    static Html home(Forecast.View view, String csrf) {
        Forecast.Month current = view.current();
        Html heading = Components.heading("Votre budget", Format.month(current.month()), null, Html.EMPTY);
        if (!view.hasIncome() && !view.hasExpenses()) {
            return Layout.page("Accueil · Patria", Layout.Section.HOME, csrf, Html.concat(heading, Components.emptyState("Votre budget est vide",
                    "Commencez par le foyer : les personnes et leurs revenus, puis les dépenses (une seule saisie par dépense, l'application prévoit les mois suivants). "
                            + "Les prêts, charges et loyers de l'immobilier sont repris automatiquement.",
                    Components.button("/administration/foyer", "Renseigner le foyer"))));
        }
        BigDecimal nonMonthly = current.expenses().stream().filter(line -> line.frequency() != Frequency.MONTHLY)
                .map(Forecast.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Une seule boite pour toutes les alertes : les plus graves en premier
        Html alerts = view.alerts().isEmpty() ? Html.EMPTY : Html.of("<section class=\"watch\"><h2>À surveiller</h2><ul>%s</ul></section>",
                view.alerts().stream().sorted(java.util.Comparator.comparing(Forecast.Alert::serious).reversed())
                        .map(alert -> Html.of("<li class=\"%s\">%s</li>", alert.serious() ? "serious" : "warning", alert.text())).collect(Html.joining()));
        Html content = Html.concat(heading,
                Components.metrics(4,
                        tile("Revenus du mois", "Revenus nets avant impôt et loyers perçus attendus ce mois-ci.", euroWhole(current.income()),
                                Format.plural(current.incomes().size(), "versement", "versements")),
                        tile("Dépenses du mois", "Toutes les dépenses prévues ce mois-ci, prêts et charges immobilières compris.", euroWhole(current.spending()),
                                nonMonthly.signum() == 0 ? "toutes mensuelles" : "dont " + euroWhole(nonMonthly) + " non mensuelles"),
                        balanceTile(current.balance(), "Revenus du mois − dépenses du mois.", "après toutes les dépenses du mois"),
                        debtTile(view.debt())),
                alerts,
                Components.card("12 prochains mois", null, Html.EMPTY, Html.concat(
                        view.provision().signum() == 0 ? Html.EMPTY : Html.of("<p class=\"provision\">À mettre de côté chaque mois pour les dépenses non mensuelles : <strong>%s</strong></p>",
                                euroWhole(view.provision())),
                        BudgetChart.render(view.months()),
                        monthList(view.months()))));
        return Layout.page("Accueil · Patria", Layout.Section.HOME, csrf, content);
    }

    private static Html balanceTile(BigDecimal balance, String hint, String sub) {
        String tone = balance.signum() < 0 ? "negative" : "positive";
        return Html.of("<article class=\"metric\">%s<strong class=\"%s\">%s</strong><small>%s</small></article>",
                Components.label("Reste à vivre", hint), tone, Format.signedEuroWhole(balance), balance.signum() < 0 ? "dépenses supérieures aux revenus" : sub);
    }

    private static Html debtTile(Forecast.DebtRatio debt) {
        if (debt.percent() == null) return tile("Taux d'endettement", "Mensualités de crédit ÷ revenus nets avant impôt (méthode bancaire).", Format.DASH, "revenus non renseignés");
        String state = debt.isAboveLimit() ? "▲ au-dessus du seuil de 35 %" : "✓ sous le seuil de 35 %";
        return Html.of("<article class=\"metric\">%s<strong>%s</strong><small class=\"%s\">%s</small></article>",
                Components.label("Taux d'endettement", "Méthode bancaire (HCSF) : mensualités de crédit assurance comprise ÷ revenus nets avant impôt, "
                        + "loyers perçus comptés à 70 %. " + euroWhole(debt.credits()) + " de crédits pour " + euroWhole(debt.income()) + " de revenus retenus."),
                Format.percent(debt.percent()), debt.isAboveLimit() ? "status-serious" : "status-good", state);
    }

    /** Detail des 12 mois : le mois en cours ouvert, les autres repliables. */
    private static Html monthList(List<Forecast.Month> months) {
        Html items = months.stream().map(month -> {
            Html incomes = month.incomes().isEmpty() ? Html.raw("<p class=\"empty\">Aucun revenu prévu.</p>")
                    : month.incomes().stream().map(line -> Html.of("<li><span>%s <small>%s</small></span><b>%s</b></li>", line.label(), line.who(), euro(line.amount())))
                    .collect(Html.joining());
            Map<Category, List<Forecast.Line>> byCategory = month.expenses().stream()
                    .collect(Collectors.groupingBy(Forecast.Line::category, () -> new EnumMap<>(Category.class), Collectors.toList()));
            Html expenses = month.expenses().isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense prévue.</p>")
                    : byCategory.entrySet().stream().map(entry -> Html.of("<li class=\"group\"><span>%s</span><b>%s</b></li>%s", entry.getKey().label(),
                    euro(entry.getValue().stream().map(Forecast.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    entry.getValue().stream().map(line -> Html.of("<li class=\"item\"><span>%s%s</span><b>%s</b></li>", line.label(),
                            line.frequency() == Frequency.MONTHLY ? Html.EMPTY : Html.of(" <small class=\"badge\">%s</small>", line.frequency().label()),
                            euro(line.amount()))).collect(Html.joining()))).collect(Html.joining());
            String tone = month.balance().signum() < 0 ? "negative" : "";
            return Html.of("""
                    <details class="month"%s><summary><span class="month-name">%s</span><span>%s</span><span>%s</span><span class="%s">%s</span></summary>
                    <div class="month-detail"><div><h3>Revenus</h3><ul>%s</ul></div><div><h3>Dépenses</h3><ul>%s</ul></div></div></details>""",
                    Html.raw(month == months.getFirst() ? " open" : ""), Format.month(month.month()), euroWhole(month.income()), euroWhole(month.spending()),
                    tone, Format.signedEuroWhole(month.balance()), incomes, expenses);
        }).collect(Html.joining());
        return Html.of("<div class=\"months\"><div class=\"months-head\"><span>Mois</span><span>Revenus</span><span>Dépenses</span><span>Reste à vivre</span></div>%s</div>", items);
    }

    // ---- Budget moyen ----

    static Html budget(Forecast.View view, List<Forecast.Average> incomes, List<Forecast.Average> expenses, String csrf) {
        BigDecimal income = incomes.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spending = expenses.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        Html heading = Components.heading("Budget", "Moyenne mensuelle", "Sur les 12 prochains mois : chaque dépense est ramenée à son équivalent par mois.",
                Components.secondaryButton("/administration/foyer", "Administrer"));

        Map<Category, BigDecimal> byCategory = new EnumMap<>(Category.class);
        for (Forecast.Average line : expenses) byCategory.merge(line.category(), line.monthly(), BigDecimal::add);
        BigDecimal largest = byCategory.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        Html bars = byCategory.entrySet().stream().sorted(Map.Entry.<Category, BigDecimal>comparingByValue().reversed())
                .map(entry -> Html.of("<div class=\"rank\"><span class=\"rank-label\">%s</span><span class=\"rank-bar\"><i style=\"width:%s%%\"></i></span><b>%s</b><small>%s</small></div>"
                                .replace("%%", "%"), entry.getKey().label(),
                        entry.getValue().multiply(BigDecimal.valueOf(100)).divide(largest, 1, RoundingMode.HALF_UP).toPlainString(),
                        euroWhole(entry.getValue()) + "/mois",
                        spending.signum() == 0 ? "" : Format.percent(entry.getValue().multiply(BigDecimal.valueOf(100)).divide(spending, 0, RoundingMode.HALF_UP))))
                .collect(Html.joining());

        Html incomeRows = incomes.stream().map(line -> Html.of("<tr>%s%s%s%s%s</tr>", cell(Components.link(line.link(), line.label())), cell(line.detail()),
                cell(line.frequency().label()), number(line.perPayment(), euro(line.perPayment())), number(line.monthly(), euro(line.monthly())))).collect(Html.joining());
        Html expenseRows = expenses.stream().sorted(Comparator.comparing(Forecast.Average::category).thenComparing(Forecast.Average::monthly, Comparator.reverseOrder()))
                .map(line -> Html.of("<tr>%s%s%s%s%s</tr>", cell(Html.concat(Components.link(line.link(), line.label()),
                                line.origin() == Forecast.Origin.REAL_ESTATE ? Html.raw(" <small class=\"badge\">Immobilier</small>") : Html.EMPTY)),
                        cell(line.detail()), cell(line.frequency().label()), number(line.perPayment(), euro(line.perPayment())), number(line.monthly(), euro(line.monthly()))))
                .collect(Html.joining());

        Html content = Html.concat(heading,
                Components.metrics(4,
                        tile("Revenus", null, euroWhole(income), "par mois en moyenne"),
                        tile("Dépenses", null, euroWhole(spending), "par mois en moyenne"),
                        balanceTile(income.subtract(spending), "Revenus moyens − dépenses moyennes sur 12 mois.", "par mois en moyenne"),
                        tile("À mettre de côté", "Moyenne mensuelle des dépenses non mensuelles (assurances annuelles, taxes...).", euroWhole(view.provision()), "chaque mois")),
                Components.card("Dépenses par catégorie", null, Html.EMPTY, byCategory.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense.</p>")
                        : Html.of("<div class=\"ranking\">%s</div>", bars)),
                Components.card("Revenus", incomes.size(), Html.EMPTY, incomes.isEmpty() ? Html.raw("<p class=\"empty\">Aucun revenu.</p>")
                        : Components.table(List.of("Revenu", "Personne", "Rythme", "#Montant", "#Par mois"), incomeRows, true, false)),
                Components.card("Dépenses", expenses.size(), Html.EMPTY, expenses.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense.</p>")
                        : Components.table(List.of("Dépense", "Catégorie", "Rythme", "#Montant", "#Par mois"), expenseRows, true, false)));
        return Layout.page("Budget · Patria", Layout.Section.BUDGET, csrf, content);
    }

    // ---- Saisie des depenses ----

    static Map<String, String> values(BudgetItem item) {
        Map<String, String> values = new HashMap<>();
        values.put("label", item.label());
        values.put("category", item.category().name());
        values.put("amount", Format.plain(item.amount()));
        values.put("frequency", item.frequency().name());
        values.put("start", item.start().toString());
        values.put("end", item.end() == null ? "" : item.end().toString());
        return values;
    }

    private static Html fields(Map<String, String> values) {
        return Components.fields(3,
                Components.input(Input.of("Libellé", "label", Field.TEXT).required().maxLength(100).placeholder("Électricité"), values),
                Components.select("Catégorie", "category", Arrays.stream(Category.values()).map(c -> new Option(c.name(), c.label())).toList(),
                        values.getOrDefault("category", Category.OTHER.name())),
                Components.input(Input.of("Montant (€)", "amount", Field.MONEY).required().help("Montant de chaque paiement."), values),
                Components.select("Fréquence", "frequency", Arrays.stream(Frequency.values()).map(f -> new Option(f.name(), f.label())).toList(),
                        values.getOrDefault("frequency", Frequency.MONTHLY.name())),
                Components.input(Input.of("Premier paiement", "start", Field.DATE).required().help("Son mois fixe le rythme : payée en mars, une dépense annuelle revient chaque mars."), values),
                Components.input(Input.of("Dernier paiement", "end", Field.DATE).help("Vide si sans fin."), values));
    }

    static Html admin(List<BudgetItem> items, Map<String, String> values, Notice notice, String csrf) {
        Html presets = Preset.ALL.stream().map(preset -> Html.of(
                "<button type=\"button\" class=\"chip preset\" data-preset-label=\"%s\" data-preset-category=\"%s\" data-preset-frequency=\"%s\">%s</button>",
                preset.label(), preset.category().name(), preset.frequency().name(), preset.label())).collect(Html.joining());
        Html form = Html.concat(Html.of("<p class=\"muted\">Modèles : un clic remplit le formulaire, il reste le montant et la date.</p><div class=\"presets\">%s</div>", presets),
                Components.form("/administration/depenses", csrf, false, fields(values), "Ajouter la dépense"));

        Map<Category, List<BudgetItem>> byCategory = items.stream()
                .collect(Collectors.groupingBy(BudgetItem::category, () -> new EnumMap<>(Category.class), Collectors.toList()));
        Html rows = byCategory.entrySet().stream().flatMap(entry -> entry.getValue().stream()).map(item -> Html.of("<tr>%s%s%s%s%s%s<td class=\"actions\"><a href=\"/depense/%s\">Modifier</a></td></tr>",
                cell(item.label()), cell(item.category().label()), number(item.amount(), euro(item.amount())), cell(item.frequency().label()),
                number(item.frequency().monthly(item.amount()), item.frequency().isRecurring() ? euro(item.frequency().monthly(item.amount())) : Format.DASH),
                cell(item.end() == null ? "depuis " + Format.date(item.start()) : Format.date(item.start()) + " → " + Format.date(item.end())), item.id())).collect(Html.joining());
        Html table = items.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense saisie.</p>")
                : Components.table(List.of("Dépense", "Catégorie", "#Montant", "Fréquence", "#Par mois", "Période", ""), rows, true, false);
        Html automatic = Html.raw("""
                <p class="muted">Repris automatiquement de l'immobilier, rien à ressaisir : mensualités des prêts, taxe foncière (octobre),
                taxe d'habitation (novembre), charges de copropriété (chaque trimestre), assurance et autres charges des biens (réparties sur 12 mois),
                loyers perçus.</p>""");

        Html content = Html.concat(Components.heading("Administration", "Dépenses", null, Html.EMPTY), Components.notice(notice),
                Components.card("Ajouter une dépense", null, Html.EMPTY, form),
                Components.card("Dépenses saisies", items.size(), Html.EMPTY, Html.concat(table, automatic)));
        return Layout.adminSection("Dépenses · Patria", csrf, Layout.AdminSection.EXPENSES, content);
    }

    static Html edit(BudgetItem item, Map<String, String> values, Notice notice, String csrf) {
        Html content = Html.concat(
                Components.backLink("/administration/depenses", "Dépenses"),
                Components.heading("Dépense", item.label(), item.category().label(), Html.EMPTY), Components.notice(notice),
                Components.card("Dépense", null, Html.EMPTY, Components.form("/depense/" + item.id(), csrf, false, fields(values), "Enregistrer")),
                Components.dangerZone("Supprimer cette dépense", "Elle ne sera plus comptée dans le budget.", "/depense/" + item.id() + "/supprimer",
                        "Supprimer la dépense « " + item.label() + " » ?", "Supprimer la dépense", csrf));
        return Layout.adminSection("Dépense · Patria", csrf, Layout.AdminSection.EXPENSES, content);
    }
}
