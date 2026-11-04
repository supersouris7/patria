package fr.patria.budget;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.tile;
import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;

import fr.patria.common.Frequency;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.loan.Loan;
import fr.patria.property.Property;
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
        Html heading = Html.concat(Components.heading(owner(view), Format.month(current.month()), null, Html.EMPTY), scopeTabs(view, "/"));
        if (!view.hasIncome() && !view.hasExpenses() && view.person() != null) {
            return Layout.page("Accueil · Patria", Layout.Section.HOME, csrf, scope(view), Html.concat(heading, Components.emptyState("Rien pour " + view.person().name(),
                    "Aucun revenu ni dépense pour cette personne. Les revenus se saisissent dans Foyer et revenus, la répartition des dépenses dans chaque dépense.",
                    Components.button("/administration/foyer", "Foyer et revenus"))));
        }
        if (!view.hasIncome() && !view.hasExpenses()) {
            return Layout.page("Accueil · Patria", Layout.Section.HOME, csrf, scope(view), Html.concat(heading, Components.emptyState("Votre budget est vide",
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
                        isAccount(view) ? tile("Taux d'endettement", "Calculé pour les personnes : les crédits payés par un compte joint comptent "
                                + "à parts égales dans le taux de chacune.", Format.DASH, "pas de taux pour un compte") : debtTile(view.debt())),
                alerts,
                Components.card("12 prochains mois", null, Html.EMPTY, Html.concat(
                        view.provision().signum() == 0 ? Html.EMPTY : Html.of("<p class=\"provision\">À mettre de côté chaque mois pour les dépenses non mensuelles : <strong>%s</strong></p>",
                                euroWhole(view.provision())),
                        BudgetChart.render(view.months()),
                        monthList(view.months(), "/" + scope(view)))));
        return Layout.page("Accueil · Patria", Layout.Section.HOME, csrf, scope(view), content);
    }

    private static boolean isAccount(Forecast.View view) {
        return view.person() != null && view.person().isAccount();
    }

    /** « Budget du foyer » ou « Budget d'Alice ». */
    private static String owner(Forecast.View view) {
        if (view.person() == null) return "Budget du foyer";
        String name = view.person().name();
        if (view.person().isAccount()) return name.toLowerCase(java.util.Locale.FRENCH).startsWith("compte") ? name : "Compte · " + name;
        return "Budget " + (name.matches("(?i)^[aeiouyhéèêàâîôû].*") ? "d'" : "de ") + name;
    }

    /** Une depense ou un revenu ouvert depuis le budget y ramene (lien de retour, puis apres enregistrement). */
    private static String withReturn(String link, String back) {
        if (link == null || !(link.startsWith("/depense/") || link.startsWith("/revenu/") || link.startsWith("/virement/"))) return link;
        return link + "?retour=" + encode(back);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Parametre de la personne affichee, repris par les liens Accueil et Budget. */
    private static String scope(Forecast.View view) {
        return view.person() == null ? "" : "?personne=" + view.person().id();
    }

    private static Html scopeTabs(Forecast.View view, String path) {
        if (view.people().size() + (view.householdTab() ? 1 : 0) < 2) return Html.EMPTY;
        Html tabs = Html.concat(view.householdTab() ? scopeTab(path, "Foyer", view.person() == null) : Html.EMPTY,
                view.people().stream().map(person -> scopeTab(path + "?personne=" + person.id(), person.name(),
                        view.person() != null && view.person().id() == person.id())).collect(Html.joining()));
        return Html.of("<nav class=\"scope-tabs\" aria-label=\"Budget de\">%s</nav>", tabs);
    }

    private static Html scopeTab(String href, String label, boolean active) {
        return Html.of("<a href=\"%s\"%s>%s</a>", href, Html.raw(active ? " class=\"active\" aria-current=\"page\"" : ""), label);
    }

    private static Html balanceTile(BigDecimal balance, String hint, String sub) {
        String tone = balance.signum() < 0 ? "negative" : "positive";
        return Html.of("<article class=\"metric\">%s<strong class=\"%s\">%s</strong><small>%s</small></article>",
                Components.label("Reste à vivre", hint), tone, Format.signedEuroWhole(balance), balance.signum() < 0 ? "dépenses supérieures aux revenus" : sub);
    }

    private static final String DEBT_HINT = "Méthode bancaire (HCSF) : mensualités de crédit assurance comprise ÷ revenus nets avant impôt, "
            + "loyers perçus comptés à 70 %. Vert sous 33 %, orange de 33 à 35 %, rouge au-delà.";

    private static Html debtTile(Forecast.DebtRatio debt) {
        if (debt.percent() == null) return tile("Taux d'endettement", DEBT_HINT, Format.DASH, "revenus non renseignés");
        return Html.of("<article class=\"metric debt %s\">%s<strong>%s</strong>%s<small>%s</small></article>",
                debtClass(debt), Components.label("Taux d'endettement", DEBT_HINT + " " + euroWhole(debt.credits()) + " de crédits pour "
                        + euroWhole(debt.income()) + " de revenus retenus."),
                Format.percent(debt.percent()), debtGauge(debt), debtState(debt));
    }

    /** Carte detaillee de la page Budget : jauge, calcul et marge avant 33 %. */
    private static Html debtCard(Forecast.DebtRatio debt) {
        if (debt.percent() == null) {
            return Components.card("Taux d'endettement", null, Html.EMPTY,
                    Html.raw("<p class=\"empty\">Renseignez les revenus du foyer pour calculer le taux d'endettement.</p>"));
        }
        BigDecimal margin = debt.marginToComfort();
        return Components.card("Taux d'endettement", null, Html.EMPTY, Html.of("""
                <div class="debt-detail %s"><div class="debt-figure"><strong>%s</strong><span>%s</span></div>%s
                <dl class="calc-grid"><div><dt>Mensualités de crédit</dt><dd>%s</dd></div><div><dt>Revenus retenus</dt><dd>%s</dd></div>                <div><dt>Marge avant 33 %%</dt><dd>%s</dd></div></dl>
                <p class="muted">%s</p></div>""".replace("%%", "%"),
                debtClass(debt), Format.percent(debt.percent()), debtState(debt), Html.concat(debtGauge(debt),
                        Html.raw("<span class=\"gauge-scale\" aria-hidden=\"true\"><span class=\"before\" style=\"left:66%\">33 %</span><span style=\"left:70%\">35 %</span></span>")),
                euroWhole(debt.credits()) + "/mois", euroWhole(debt.income()) + "/mois",
                margin.signum() == 0 ? "aucune" : euroWhole(margin) + "/mois",
                DEBT_HINT));
    }

    private static String debtClass(Forecast.DebtRatio debt) {
        return switch (debt.level()) {
            case GOOD -> "level-good";
            case WARNING -> "level-warning";
            case SERIOUS -> "level-serious";
        };
    }

    private static String debtState(Forecast.DebtRatio debt) {
        return switch (debt.level()) {
            case GOOD -> "✓ sous 33 %";
            case WARNING -> "▲ entre 33 et 35 %";
            case SERIOUS -> "▲ au-dessus de 35 %";
        };
    }

    /** Jauge de 0 a 50 % avec les reperes 33 % et 35 % ; la valeur reste lisible en texte a cote. */
    private static Html debtGauge(Forecast.DebtRatio debt) {
        double scale = 50;
        double fill = Math.min(debt.percent().doubleValue(), scale) / scale * 100;
        return Html.of("<span class=\"gauge\" aria-hidden=\"true\"><i class=\"gauge-fill\" style=\"width:%s%%\"></i>"
                .replace("%%", "%") + "<i class=\"gauge-mark\" style=\"left:66%\"></i><i class=\"gauge-mark\" style=\"left:70%\"></i></span>",
                String.format(java.util.Locale.ROOT, "%.1f", fill));
    }

    /** Detail des 12 mois : le mois en cours ouvert, les autres repliables. Chaque ligne mene a sa saisie, puis ramene a {@code back}. */
    private static Html monthList(List<Forecast.Month> months, String back) {
        Html items = months.stream().map(month -> {
            Html incomes = month.incomes().isEmpty() ? Html.raw("<p class=\"empty\">Aucun revenu prévu.</p>")
                    : month.incomes().stream().map(line -> Html.of("<li><span>%s <small>%s</small></span><b>%s</b></li>", lineLink(line.link(), line.label(), back), line.who(), euro(line.amount())))
                    .collect(Html.joining());
            Map<Category, List<Forecast.Line>> byCategory = month.expenses().stream()
                    .collect(Collectors.groupingBy(Forecast.Line::category, () -> new EnumMap<>(Category.class), Collectors.toList()));
            Html expenses = month.expenses().isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense prévue.</p>")
                    : byCategory.entrySet().stream().map(entry -> Html.of("<li class=\"group\"><span>%s</span><b>%s</b></li>%s", entry.getKey().label(),
                    euro(entry.getValue().stream().map(Forecast.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    entry.getValue().stream().map(line -> Html.of("<li class=\"item\"><span>%s%s</span><b>%s</b></li>", lineLink(line.link(), line.label(), back),
                            line.frequency() == Frequency.MONTHLY ? Html.EMPTY : Html.of(" <small class=\"badge\">%s</small>", line.frequency().label()),
                            euro(line.amount()))).collect(Html.joining()))).collect(Html.joining());
            String tone = month.balance().signum() < 0 ? "negative" : "";
            return Html.of("""
                    <details class="month" id="%s"%s><summary><span class="month-name">%s</span><span>%s</span><span>%s</span><span class="%s">%s</span></summary>
                    <div class="month-detail"><div><h3>Revenus</h3><ul>%s</ul></div><div><h3>Dépenses</h3><ul>%s</ul></div>                    <p class="month-margin %s"><span>Revenus %s − dépenses %s</span><span>%s <b>%s</b></span></p></div></details>""",
                    BudgetChart.anchor(month.month()), Html.raw(month == months.getFirst() ? " open" : ""), Format.month(month.month()), euroWhole(month.income()), euroWhole(month.spending()),
                    tone, Format.signedEuroWhole(month.balance()), incomes, expenses,
                    tone, euro(month.income()), euro(month.spending()), month.balance().signum() < 0 ? "Il manque" : "Reste à vivre",
                    Format.signedEuroWhole(month.balance()));
        }).collect(Html.joining());
        return Html.of("<div class=\"months\"><div class=\"months-head\"><span>Mois</span><span>Revenus</span><span>Dépenses</span><span>Reste à vivre</span></div>%s</div>", items);
    }

    /** Detail d'une categorie du budget moyen : ses depenses, de la plus lourde a la plus legere. */
    private static Html categoryDetail(Category category, List<Forecast.Average> expenses, Forecast.View view) {
        Html items = expenses.stream().filter(line -> line.category() == category).sorted(Comparator.comparing(Forecast.Average::monthly).reversed())
                .map(line -> Html.of("<li><span>%s <small>%s · %s</small></span><b>%s</b></li>", lineLink(line.link(), line.label(), "/budget" + scope(view)),
                        line.frequency().label(), euro(line.perPayment()), euro(line.monthly()) + "/mois"))
                .collect(Html.joining());
        return Html.of("<ul class=\"rank-detail\">%s</ul>", items);
    }

    /** Libelle cliquable : la saisie de la ligne (depense, revenu, virement, charges du bien), avec retour ici. */
    private static Html lineLink(String link, String label, String back) {
        if (link == null || !Layout.canOpen(link)) return Html.of("%s", label);
        return Components.link(withReturn(link, back), label);
    }

    // ---- Budget moyen ----

    static Html budget(Forecast.View view, List<Forecast.Average> incomes, List<Forecast.Average> expenses, String csrf) {
        BigDecimal income = incomes.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spending = expenses.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        Html heading = Html.concat(Components.heading(owner(view), "Moyenne mensuelle", "Sur les 12 prochains mois : chaque dépense est ramenée à son équivalent par mois."
                        + (view.person() == null ? "" : " Part de " + view.person().name() + " seulement."),
                Components.secondaryButton("/administration/foyer", "Administrer")), scopeTabs(view, "/budget"));

        Map<Category, BigDecimal> byCategory = new EnumMap<>(Category.class);
        for (Forecast.Average line : expenses) byCategory.merge(line.category(), line.monthly(), BigDecimal::add);
        BigDecimal largest = byCategory.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ONE);
        Html bars = byCategory.entrySet().stream().sorted(Map.Entry.<Category, BigDecimal>comparingByValue().reversed())
                .map(entry -> Html.of(("<details class=\"rank-group\"><summary class=\"rank\"><span class=\"rank-label\">%s</span><span class=\"rank-bar\"><i style=\"width:%s%%\"></i></span>"
                                + "<b>%s</b><small>%s</small></summary>%s</details>").replace("%%", "%"), entry.getKey().label(),
                        entry.getValue().multiply(BigDecimal.valueOf(100)).divide(largest, 1, RoundingMode.HALF_UP).toPlainString(),
                        euroWhole(entry.getValue()) + "/mois",
                        spending.signum() == 0 ? "" : Format.percent(entry.getValue().multiply(BigDecimal.valueOf(100)).divide(spending, 0, RoundingMode.HALF_UP)),
                        categoryDetail(entry.getKey(), expenses, view)))
                .collect(Html.joining());

        Html incomeRows = incomes.stream().map(line -> Html.of("<tr>%s%s%s%s%s</tr>", cell(lineLink(line.link(), line.label(), "/budget" + scope(view))), cell(line.detail()),
                cell(line.frequency().label()), number(line.perPayment(), euro(line.perPayment())), number(line.monthly(), euro(line.monthly())))).collect(Html.joining());
        Html expenseRows = expenses.stream().sorted(Comparator.comparing(Forecast.Average::category).thenComparing(Forecast.Average::monthly, Comparator.reverseOrder()))
                .map(line -> Html.of("<tr>%s%s%s%s%s</tr>", cell(Html.concat(lineLink(line.link(), line.label(), "/budget" + scope(view)),
                                line.origin() == Forecast.Origin.REAL_ESTATE ? Html.raw(" <small class=\"badge\">Immobilier</small>") : Html.EMPTY)),
                        cell(line.detail()), cell(line.frequency().label()), number(line.perPayment(), euro(line.perPayment())), number(line.monthly(), euro(line.monthly()))))
                .collect(Html.joining());

        Html content = Html.concat(heading,
                Components.metrics(4,
                        tile("Revenus", null, euroWhole(income), "par mois en moyenne"),
                        tile("Dépenses", null, euroWhole(spending), "par mois en moyenne"),
                        balanceTile(income.subtract(spending), "Revenus moyens − dépenses moyennes sur 12 mois.", "par mois en moyenne"),
                        tile("À mettre de côté", "Moyenne mensuelle des dépenses non mensuelles (assurances annuelles, taxes...).", euroWhole(view.provision()), "chaque mois")),
                isAccount(view) ? Html.EMPTY : debtCard(view.debt()),
                Components.card("Dépenses par catégorie", null, Html.EMPTY, byCategory.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense.</p>")
                        : Html.of("<div class=\"ranking\">%s</div>", bars)),
                Components.card("Revenus", incomes.size(), Html.EMPTY, incomes.isEmpty() ? Html.raw("<p class=\"empty\">Aucun revenu.</p>")
                        : Components.table(List.of("Revenu", "Personne", "Rythme", "#Montant", "#Par mois"), incomeRows, true, false)),
                Components.card("Dépenses", expenses.size(), Html.EMPTY, expenses.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense.</p>")
                        : Components.table(List.of("Dépense", "Catégorie", "Rythme", "#Montant", "#Par mois"), expenseRows, true, false)));
        return Layout.page("Budget · Patria", Layout.Section.BUDGET, csrf, scope(view), content);
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

    /** Champs de repartition : une part par personne, vides = parts egales. */
    static Html shareFields(List<Person> people, Map<String, String> values, String emptyHint) {
        if (people.size() < 2) return Html.EMPTY;
        Html inputs = people.stream().map(person -> Html.of("<label class=\"share-input\"><span>%s</span><span class=\"suffix\"><input name=\"%s\" value=\"%s\" inputmode=\"numeric\" placeholder=\"%s\">%%</span></label>"
                        .replace("%%", "%"), person.name(), Shares.field(person), values.getOrDefault(Shares.field(person), ""),
                Shares.percent(Shares.weight(Map.of(), people, person.id())).replace("\u00a0%", "")))
                .collect(Html.joining());
        return Html.of("<fieldset class=\"shares\"><legend>Répartition</legend><div class=\"share-inputs\">%s</div><span class=\"help\">%s</span></fieldset>", inputs, emptyHint);
    }

    private static Html fields(Map<String, String> values, List<Person> people) {
        return Html.concat(fields(values), shareFields(people, values, "Laisser vide pour partager à parts égales. Sinon, des parts qui font 100 % au total."));
    }

    private static Html fields(Map<String, String> values) {
        return Components.fields(3,
                Components.input(Input.of("Libellé", "label", Field.TEXT).required().maxLength(100).placeholder("Électricité"), values),
                Components.select("Catégorie", "category", Category.selectable().stream().map(c -> new Option(c.name(), c.label())).toList(),
                        values.getOrDefault("category", Category.OTHER.name())),
                Components.input(Input.of("Montant (€)", "amount", Field.MONEY).required().help("Montant de chaque paiement."), values),
                Components.select("Fréquence", "frequency", Arrays.stream(Frequency.values()).map(f -> new Option(f.name(), f.label())).toList(),
                        values.getOrDefault("frequency", Frequency.MONTHLY.name())),
                Components.input(Input.of("Premier paiement", "start", Field.DATE).required().help("Son mois fixe le rythme : payée en mars, une dépense annuelle revient chaque mars."), values),
                Components.input(Input.of("Dernier paiement", "end", Field.DATE).help("Vide si sans fin."), values));
    }

    /**
     * {@code people} : tous les membres (colonne Repartition) ; {@code formPeople} : ceux proposes a la saisie (vues
     * ouvertes) ; {@code readOnly} : depenses affichees sans lien de modification.
     */
    static Html admin(List<BudgetItem> items, Map<Shares.Key, Map<Long, Integer>> shares, List<Person> people, List<Person> formPeople,
                      java.util.Set<Long> readOnly, List<Property> properties, Map<String, String> values, Notice notice, String csrf) {
        Html presets = Preset.ALL.stream().map(preset -> Html.of(
                "<button type=\"button\" class=\"chip preset\" data-preset-label=\"%s\" data-preset-category=\"%s\" data-preset-frequency=\"%s\">%s</button>",
                preset.label(), preset.category().name(), preset.frequency().name(), preset.label())).collect(Html.joining());
        Html form = Html.concat(Html.of("<p class=\"muted\">Modèles : un clic remplit le formulaire, il reste le montant et la date.</p><div class=\"presets\">%s</div>", presets),
                Components.form("/administration/depenses", csrf, false, fields(values, formPeople), "Ajouter la dépense"));

        Map<Category, List<BudgetItem>> byCategory = items.stream()
                .collect(Collectors.groupingBy(BudgetItem::category, () -> new EnumMap<>(Category.class), Collectors.toList()));
        boolean shared = people.size() >= 2;
        Html rows = byCategory.entrySet().stream().flatMap(entry -> entry.getValue().stream()).map(item -> Html.of("<tr>%s%s%s%s%s%s<td class=\"actions\">%s</td></tr>",
                Html.concat(cell(item.label()), shared ? cell(Shares.describe(shares.getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of()), people)) : Html.EMPTY),
                cell(item.category().label()), number(item.amount(), euro(item.amount())), cell(item.frequency().label()),
                number(item.frequency().monthly(item.amount()), item.frequency().isRecurring() ? euro(item.frequency().monthly(item.amount())) : Format.DASH),
                cell(item.end() == null ? "depuis " + Format.date(item.start()) : Format.date(item.start()) + " → " + Format.date(item.end())),
                readOnly.contains(item.id()) ? Html.of("<a href=\"/depense/%s\" title=\"Partagée avec un membre dont la vue ne vous est pas ouverte\">Voir</a>", item.id())
                        : Html.of("<a href=\"/depense/%s\">Modifier</a>", item.id()))).collect(Html.joining());
        Html table = items.isEmpty() ? Html.raw("<p class=\"empty\">Aucune dépense saisie.</p>")
                : Components.table(shared ? List.of("Dépense", "Répartition", "Catégorie", "#Montant", "Fréquence", "#Par mois", "Période", "")
                        : List.of("Dépense", "Catégorie", "#Montant", "Fréquence", "#Par mois", "Période", ""), rows, true, false);
        Html automatic = Html.raw("""
                <p class="muted">Repris automatiquement de l'immobilier, rien à ressaisir : mensualités des prêts, taxe foncière (octobre, ou de janvier
                à octobre si mensualisée), taxe d'habitation (novembre), charges de copropriété (chaque trimestre), assurance et autres charges des biens
                (réparties sur 12 mois), loyers perçus. Ne les ajoutez pas ici : elles compteraient deux fois.</p>""");
        Html taxLinks = properties.isEmpty() || !Layout.isAdmin() ? Html.EMPTY : Html.of("<p class=\"tax-links\">Taxe foncière et charges : %s</p>",
                java.util.stream.IntStream.range(0, properties.size()).mapToObj(index -> Html.concat(index == 0 ? Html.EMPTY : Html.raw(" · "),
                        Components.link("/administration/bien/" + properties.get(index).id() + "/charges", properties.get(index).name()))).collect(Html.joining()));

        Html content = Html.concat(Components.heading("Administration", "Dépenses", null, Html.EMPTY), Components.notice(notice),
                Components.card("Ajouter une dépense", null, Html.EMPTY, form),
                Components.card("Importer un fichier CSV", null, Components.secondaryButton("/administration/depenses/modele.csv", "Télécharger le modèle"),
                        importForm(csrf)),
                Components.card("Dépenses saisies", items.size(), Html.EMPTY, Html.concat(table, automatic, taxLinks)));
        return Layout.adminSection("Dépenses · Patria", csrf, Layout.AdminSection.EXPENSES, content);
    }

    private static Html importForm(String csrf) {
        Html help = Html.raw("""
                <p class="muted">Plusieurs dépenses d'un coup, depuis un tableur : une ligne par dépense, colonnes
                dans cet ordre et <b>sans ligne d'en-tête</b> : <code>libellé;catégorie;montant;fréquence;début;fin</code>.
                Exemple : <code>Mutuelle;Santé;35,69;Mensuel;01/01/2026;</code>. L'aperçu permet de choisir à qui reviennent
                les dépenses avant tout enregistrement.</p>""");
        return Html.concat(help, Components.llmPrompt("prompt-depenses", ExpenseCsv.llmPrompt()), Components.form("/administration/depenses/import", csrf, true, Components.fileInput("Fichier CSV", "csv"), "Voir l'aperçu"));
    }

    /**
     * Apercu d'un import : chaque ligne lue et ses erreurs ; confirmation seulement si tout est valide. Avec plusieurs
     * personnes, on choisit a qui reviennent les depenses : pour tout le fichier, puis ligne par ligne si besoin.
     */
    static Html importPreview(List<ExpenseCsv.Row> rows, String content, List<Person> people, Map<String, String> choices, String csrf) {
        boolean shared = people.size() >= 2;
        long errors = rows.stream().filter(row -> !row.valid()).count();
        long duplicates = rows.stream().filter(row -> row.valid() && row.duplicate()).count();
        long fresh = rows.size() - errors - duplicates;
        boolean confirmable = errors == 0 && fresh > 0;
        String formId = "import-form";
        Html body = rows.stream().map(row -> {
            if (!row.valid()) {
                return Html.of("<tr class=\"row-error\"><td>%s</td><td colspan=\"%s\" class=\"negative\">%s</td></tr>", row.line(), shared ? 7 : 6, row.error());
            }
            BudgetItem item = row.item();
            Html owner = !shared ? Html.EMPTY : cell(row.fresh() && confirmable
                    ? ownerSelect("attribution_" + row.line(), "Comme ci-dessus", people, choices.getOrDefault("attribution_" + row.line(), ""), formId)
                    : Html.text(Format.DASH));
            return Html.of("<tr><td>%s</td>%s%s%s%s%s%s<td>%s</td></tr>", row.line(), cell(item.label()), cell(item.category().label()),
                    number(item.amount(), euro(item.amount())), cell(item.frequency().label()),
                    cell(item.end() == null ? "depuis " + Format.date(item.start()) : Format.date(item.start()) + " → " + Format.date(item.end())), owner,
                    row.duplicate() ? Html.raw("<span class=\"badge\">Déjà saisie, ignorée</span>") : Html.raw("<span class=\"badge paid\">À importer</span>"));
        }).collect(Html.joining());
        List<String> columns = new java.util.ArrayList<>(List.of("Ligne", "Dépense", "Catégorie", "#Montant", "Fréquence", "Période"));
        if (shared) columns.add("Attribuée à");
        columns.add("État");
        String summary = fresh + " dépense" + (fresh > 1 ? "s" : "") + " à importer"
                + (duplicates > 0 ? ", " + duplicates + " déjà saisie" + (duplicates > 1 ? "s" : "") + " (ignorée" + (duplicates > 1 ? "s" : "") + ")" : "")
                + (errors > 0 ? ", " + errors + " ligne" + (errors > 1 ? "s" : "") + " en erreur" : "") + ".";
        Html before = Html.EMPTY;
        Html actions;
        if (errors > 0) {
            actions = Html.concat(Html.raw("<p class=\"notice error\" role=\"alert\">Rien n'est enregistré : corrigez les lignes en erreur dans le fichier, puis importez-le de nouveau.</p>"),
                    Components.form("/administration/depenses/import", csrf, true, Components.fileInput("Fichier corrigé", "csv"), "Voir l'aperçu"));
        } else if (fresh == 0) {
            actions = Html.raw("<p class=\"muted\">Toutes ces dépenses sont déjà saisies : rien à importer.</p>");
        } else {
            if (shared) {
                before = Html.of("<div class=\"import-owner\">%s<p class=\"muted\">Pour tout le fichier ; une ligne peut avoir son propre choix dans le tableau. "
                        + "Modifiable ensuite dans chaque dépense.</p></div>", ownerSelect("attribution", null, people, choices.getOrDefault("attribution", ""), formId));
            }
            String encoded = java.util.Base64.getUrlEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            actions = Html.of("<form id=\"%s\" method=\"post\" action=\"/administration/depenses/import/valider\" class=\"form-actions\">%s<input type=\"hidden\" name=\"contenu\" value=\"%s\">"
                    + "<a class=\"button secondary\" href=\"/administration/depenses\">Annuler</a><button type=\"submit\">Importer %s</button></form>",
                    formId, Components.csrfField(csrf), encoded, fresh + " dépense" + (fresh > 1 ? "s" : ""));
        }
        Html page = Html.concat(Components.backLink("/administration/depenses", "Dépenses"),
                Components.heading("Administration", "Aperçu de l'import", summary, Html.EMPTY),
                Components.card("Lignes du fichier", rows.size(), Html.EMPTY, Html.concat(before, Components.table(columns, body, false, false), actions)));
        return Layout.adminSection("Import de dépenses · Patria", csrf, Layout.AdminSection.EXPENSES, page);
    }

    /** Choix d'attribution : parts egales ou une personne seule ; {@code inherit} ajoute « comme ci-dessus » (valeur vide). */
    private static Html ownerSelect(String name, String inherit, List<Person> people, String selected, String formId) {
        List<Option> options = new java.util.ArrayList<>();
        if (inherit != null) options.add(new Option("", inherit));
        options.add(new Option(inherit == null ? "" : "egal", "Parts égales"));
        people.forEach(person -> options.add(new Option(String.valueOf(person.id()), person.name() + " (100 %)")));
        Html choices = options.stream().map(option -> option.value().equals(selected)
                ? Html.of("<option value=\"%s\" selected>%s</option>", option.value(), option.label())
                : Html.of("<option value=\"%s\">%s</option>", option.value(), option.label())).collect(Html.joining());
        return inherit == null
                ? Html.of("<label>Attribuer les dépenses à<select name=\"%s\" form=\"%s\">%s</select></label>", name, formId, choices)
                : Html.of("<select class=\"row-owner\" name=\"%s\" form=\"%s\" aria-label=\"Attribution de la ligne\">%s</select>", name, formId, choices);
    }

    /** {@code back} : page d'ou l'on vient (le budget), {@code null} pour revenir a la liste des depenses. */
    static Html edit(BudgetItem item, List<Person> people, Map<String, String> values, Notice notice, String back, boolean readOnly, String csrf) {
        String suffix = back == null ? "" : "?retour=" + encode(back);
        Html content = Html.concat(
                back == null ? Components.backLink("/administration/depenses", "Dépenses") : Components.backLink(back, "Budget"),
                Components.heading("Dépense", item.label(), item.category().label(), Html.EMPTY), Components.notice(notice),
                Components.card("Dépense", null, Html.EMPTY, Components.formOrView(readOnly, "/depense/" + item.id() + suffix, csrf, fields(values, people), "Enregistrer")),
                readOnly ? Html.EMPTY : Components.dangerZone("Supprimer cette dépense", "Elle ne sera plus comptée dans le budget.", "/depense/" + item.id() + "/supprimer" + suffix,
                        "Supprimer la dépense « " + item.label() + " » ?", "Supprimer la dépense", csrf));
        return Layout.adminSection("Dépense · " + item.label() + " · Patria", csrf, Layout.AdminSection.EXPENSES, content);
    }

    // ---- Repartition des biens et des prets ----

    /** Ligne refusee a l'enregistrement : la saisie est reaffichee avec son message, sur la ligne meme. */
    record Rejected(Shares.Key key, Map<String, String> values, String message) {
    }

    static Html shares(List<Property> properties, List<Loan> loans, Map<Shares.Key, Map<Long, Integer>> shares, Map<Long, Long> taxPayers,
                       List<Person> people, Notice notice, String csrf) {
        return shares(properties, loans, shares, taxPayers, people, notice, null, csrf);
    }

    static Html shares(List<Property> properties, List<Loan> loans, Map<Shares.Key, Map<Long, Integer>> shares, Map<Long, Long> taxPayers,
                       List<Person> people, Notice notice, Rejected rejected, String csrf) {
        Html heading = Components.heading("Administration", "Répartition", "Part de chaque personne dans les biens et les prêts : "
                + "elle s'applique aux mensualités, charges et loyers dans le budget de chacun.", Html.EMPTY);
        if (people.size() < 2) {
            return Layout.adminSection("Répartition · Patria", csrf, Layout.AdminSection.SHARES, Html.concat(heading,
                    Components.emptyState("Une seule personne dans le foyer", "La répartition sert à partir de deux personnes.",
                            Components.button("/administration/foyer", "Ajouter une personne"))));
        }
        if (properties.isEmpty()) {
            return Layout.adminSection("Répartition · Patria", csrf, Layout.AdminSection.SHARES, Html.concat(heading, Components.notice(notice),
                    Html.raw("<p class=\"empty\">Aucun bien. Les dépenses se répartissent directement dans leur formulaire.</p>")));
        }
        Html rows = properties.stream().map(property -> {
            Map<Long, Integer> own = shares.getOrDefault(new Shares.Key(Shares.Subject.PROPERTY, property.id()), Map.of());
            Html propertyRow = shareRow("/administration/repartition/bien/" + property.id(), property.name(), Shares.describe(own, people),
                    Shares.values(own, people), people, Map.of(), false, rejected(rejected, Shares.Subject.PROPERTY, property.id()), csrf);
            Html loanRows = loans.stream().filter(loan -> loan.propertyId() == property.id()).map(loan -> {
                Map<Long, Integer> loanShare = shares.getOrDefault(new Shares.Key(Shares.Subject.LOAN, loan.id()), Map.of());
                String description = Shares.isEqual(loanShare, people) ? "Comme le bien" : Shares.describe(loanShare, people);
                return shareRow("/administration/repartition/pret/" + loan.id(), loan.name(), description, Shares.values(loanShare, people), people, own, true,
                        rejected(rejected, Shares.Subject.LOAN, loan.id()), csrf);
            }).collect(Html.joining());
            return Html.of("<div class=\"share-group\">%s%s%s</div>", propertyRow, loanRows, taxPayerRow(property, taxPayers.get(property.id()), people, csrf));
        }).collect(Html.joining());
        Html content = Html.concat(heading, Components.notice(notice),
                Components.card("Biens et prêts", null, Html.EMPTY, Html.concat(Html.of("<div class=\"share-list\">%s</div>", rows),
                        Html.raw("<p class=\"source\">Champs vides : parts égales pour un bien, même répartition que le bien pour un prêt. "
                                + "Sinon, des parts qui font 100 % au total. La taxe foncière peut être prélevée en entier sur un membre (compte joint...). "
                                + "Les dépenses saisies à la main se répartissent dans leur propre formulaire.</p>"))));
        return Layout.adminSection("Répartition · Patria", csrf, Layout.AdminSection.SHARES, content);
    }

    /** Une ligne : nom, repartition en vigueur, une part par personne (le texte grise montre la valeur par defaut). */
    private static Rejected rejected(Rejected rejected, Shares.Subject subject, long id) {
        return rejected != null && rejected.key().equals(new Shares.Key(subject, id)) ? rejected : null;
    }

    /** Taxe fonciere : prelevee sur un membre choisi (compte joint...), ou repartie comme le bien. */
    private static Html taxPayerRow(Property property, Long payer, List<Person> people, String csrf) {
        List<Option> options = new java.util.ArrayList<>();
        options.add(new Option("", "Comme le bien"));
        for (Person person : people) options.add(new Option(String.valueOf(person.id()), person.name()));
        String description = payer == null ? "Répartie comme le bien"
                : "Toute sur " + people.stream().filter(person -> person.id() == payer).map(Person::name).findFirst().orElse("");
        return Html.of("""
                <form method="post" action="/administration/repartition/bien/%s/taxe-fonciere" class="share-row loan">%s<div class="share-name"><b>Taxe foncière · prélevée sur</b>                <small>%s</small></div><div class="share-inputs">%s</div><button type="submit" class="secondary">Enregistrer</button></form>""",
                property.id(), Components.csrfField(csrf), description,
                Components.select("Prélevée sur", "payer", options, payer == null ? "" : String.valueOf(payer)));
    }

    private static Html shareRow(String action, String name, String description, Map<String, String> saved, List<Person> people,
                                 Map<Long, Integer> fallback, boolean loan, Rejected rejected, String csrf) {
        Map<String, String> values = rejected == null ? saved : rejected.values();
        Html inputs = people.stream().map(person -> Html.of(
                "<label class=\"share-input\"><span>%s</span><span class=\"suffix\"><input name=\"%s\" value=\"%s\" inputmode=\"numeric\" placeholder=\"%s\">%%</span></label>"
                        .replace("%%", "%"), person.name(), Shares.field(person), values.getOrDefault(Shares.field(person), ""),
                Shares.percent(Shares.weight(fallback, people, person.id())).replace("\u00a0%", ""))).collect(Html.joining());
        return Html.of("""
                <form method="post" action="%s" class="share-row%s">%s<div class="share-name"><b>%s</b><small>%s</small></div>\
                <div class="share-inputs">%s</div><button type="submit" class="secondary">Enregistrer</button>%s</form>""",
                action, Html.raw((loan ? " loan" : "") + (rejected == null ? "" : " invalid")), Components.csrfField(csrf), loan ? "Prêt · " + name : name,
                description, inputs, rejected == null ? Html.EMPTY : Html.of("<p class=\"share-error\" role=\"alert\">%s</p>", rejected.message()));
    }
}
