package fr.patria.wizard;

import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.Category;
import fr.patria.budget.ExpenseCsv;
import fr.patria.common.Frequency;
import fr.patria.expense.ExpenseKind;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.loan.CsvScheduleParser;
import fr.patria.property.Property;
import fr.patria.property.PropertyKind;
import fr.patria.rent.Occupancy;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Components.Option;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Pages de l'assistant d'import. HTML uniquement : le contexte est calcule par le controleur. */
final class WizardPages {
    private WizardPages() {
    }

    // ---- Accueil ----

    static Html home(WizardController.Context context) {
        List<Step> steps = context.steps().stream().filter(step -> step != Step.SUMMARY).toList();
        long handled = steps.stream().filter(context.progress()::containsKey).count();
        int percent = steps.isEmpty() ? 0 : (int) (handled * 100 / steps.size());
        boolean started = handled > 0 || !context.documents().isEmpty();
        Step resume = context.resume();
        Html actions = Html.concat(
                Components.button(resume.path(), started ? "Reprendre où j'en étais" : "Commencer"),
                Components.secondaryButton(Step.DOCUMENTS.path() + "?mode=" + WizardController.REVIEW, "Tout revoir, étape par étape"),
                Components.secondaryButton("/assistant/revue-annuelle", "Revue annuelle"));
        Html list = steps.stream().map(step -> Html.of("""
                        <li class="wizard-overview-item %s"><a href="%s"><span class="wizard-dot" aria-hidden="true">%s</span><span><b>%s</b><small>%s</small></span>\
                        <span class="wizard-state">%s</span></a></li>""",
                css(context, step), step.path(), dot(context, step), step.title(), step.summary(), state(context, step))).collect(Html.joining());
        Html intro = Html.raw("""
                <p class="lead">Le guide vous accompagne pour remplir Patria à partir de vos papiers : d'abord la liste des documents à rassembler,
                puis, étape par étape, vos revenus, votre logement, vos dépenses fixes et vos impôts. Tout se saisit ici, sans chercher dans les menus.</p>
                <ul class="wizard-promises"><li><b>Chaque étape peut être passée</b> et reprise plus tard.</li>
                <li><b>Rien n'est obligatoire</b> : ce que vous saisissez améliore le prévisionnel, le reste peut attendre.</li>
                <li><b>Votre progression est gardée</b> : « Reprendre » saute ce qui est fait, « Tout revoir » repasse chaque étape avec ce qui est déjà saisi
                (utile une fois par an).</li>
                <li>Comptez 15 à 30 minutes avec les documents sous la main.</li>
                <li><b>Un assistant IA externe</b> (ChatGPT, Claude, Le Chat…) n'est utile que pour transformer des PDF en fichier à importer : l'échéancier d'un prêt, ou vos relevés bancaires d'un coup. Tout le reste se saisit à la main. Patria ne lui envoie rien : <a href="/confidentialite">comment protéger vos données</a>.</li></ul>""");
        Html content = Html.concat(Components.heading("Patria", "Guide de saisie", context.admin() ? "Pour tout le foyer." : "Pour vos propres revenus et dépenses.",
                        actions),
                Components.card("Comment ça marche", null, Html.EMPTY, intro),
                Components.card("Les étapes", null, Html.of("<span class=\"muted\">%s sur %s · %s %%</span>".replace("%%", "%"), handled, steps.size(), percent),
                        Html.concat(Components.progress(percent), Html.of("<ol class=\"wizard-overview\">%s</ol>", list),
                                Html.of("<form method=\"post\" action=\"/assistant/recommencer\" class=\"wizard-reset\" data-confirm=\"%s\">%s"
                                                + "<button class=\"quiet\" type=\"submit\">Recommencer à zéro</button><span class=\"muted\">efface la progression, pas les données saisies</span></form>",
                                        "Effacer la progression du guide ? Les revenus, dépenses et biens saisis restent.", Components.csrfField(context.csrf())))));
        return Layout.page("Guide de saisie · Patria", Layout.Section.ASSISTANT, context.csrf(), content);
    }

    // ---- Une etape ----

    static Html step(WizardController.Context context, Step step, Notice notice, Map<String, String> values) {
        Html body = switch (step) {
            case DOCUMENTS -> documents(context);
            case MEMBERS -> members(context, values);
            case INCOMES -> incomes(context, values);
            case HOUSING -> housing(context, values);
            case PROPERTIES -> properties(context, values);
            case LOANS -> loans(context);
            case CHARGES -> charges(context, values);
            case FIXED -> fixed(context, values);
            case TAXES -> taxes(context, values);
            case TRANSFERS -> transfers(context, values);
            case SHARES -> shares(context);
            case SUMMARY -> summary(context);
        };
        int number = context.steps().indexOf(step) + 1;
        Html heading = Components.heading("Guide · étape " + number + " sur " + context.steps().size() + (context.review() ? " · revue" : ""),
                step.title(), step.summary(), Html.EMPTY);
        Html content = Html.concat(heading, Components.notice(notice), body, step == Step.SUMMARY ? Html.EMPTY : footer(context, step));
        Html layout = Html.of("<div class=\"wizard\"><div class=\"wizard-steps\" role=\"navigation\" aria-label=\"Étapes du guide\"><a class=\"wizard-home\" href=\"/assistant\">← Guide de saisie</a>"
                + "<ol>%s</ol></div><div class=\"wizard-body\">%s</div></div>", stepper(context, step), content);
        return Layout.page(step.title() + " · Guide de saisie · Patria", Layout.Section.ASSISTANT, context.csrf(), layout);
    }

    private static Html stepper(WizardController.Context context, Step current) {
        return context.steps().stream().map(step -> Html.of("<li class=\"%s%s\"><a href=\"%s%s\"%s><span class=\"wizard-dot\" aria-hidden=\"true\">%s</span>%s</a></li>",
                css(context, step), step == current ? " current" : "", step.path(), context.mode(),
                Html.raw(step == current ? " aria-current=\"step\"" : ""), dot(context, step), step.title())).collect(Html.joining());
    }

    /** Bas de page : precedent, passer, etape terminee. */
    private static Html footer(WizardController.Context context, Step step) {
        Step previous = context.previous(step);
        Html back = previous == null ? Html.raw("<span></span>") : Html.of("<a class=\"button secondary\" href=\"%s%s\">← %s</a>", previous.path(), context.mode(), previous.title());
        return Html.of("""
                <div class="wizard-footer">%s<div class="wizard-next">\
                <form method="post" action="%s/passer">%s%s<button class="quiet" type="submit">Passer cette étape</button></form>\
                <form method="post" action="%s/terminer">%s%s<button type="submit">Étape terminée →</button></form></div></div>""",
                back, step.path(), Components.csrfField(context.csrf()), modeField(context), step.path(), Components.csrfField(context.csrf()), modeField(context));
    }

    private static Html modeField(WizardController.Context context) {
        return context.review() ? Html.of("<input type=\"hidden\" name=\"mode\" value=\"%s\">", WizardController.REVIEW) : Html.EMPTY;
    }

    private static Html form(WizardController.Context context, String action, boolean multipart, Html body, String submit) {
        return Components.form(action, context.csrf(), multipart, Html.concat(modeField(context), body), submit);
    }

    private static String css(WizardController.Context context, Step step) {
        WizardRepository.Status status = context.progress().get(step);
        return status == WizardRepository.Status.DONE ? "done" : status == WizardRepository.Status.SKIPPED ? "skipped" : "todo";
    }

    private static String dot(WizardController.Context context, Step step) {
        WizardRepository.Status status = context.progress().get(step);
        return status == WizardRepository.Status.DONE ? "✓" : status == WizardRepository.Status.SKIPPED ? "↷" : String.valueOf(context.steps().indexOf(step) + 1);
    }

    private static String state(WizardController.Context context, Step step) {
        WizardRepository.Status status = context.progress().get(step);
        return status == WizardRepository.Status.DONE ? "Fait" : status == WizardRepository.Status.SKIPPED ? "Passé" : "À faire";
    }

    private static Html explain(String text) {
        return Html.of("<p class=\"wizard-explain\">%s</p>", text);
    }

    // ---- 1. Documents ----

    private static Html documents(WizardController.Context context) {
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        long ready = documents.stream().filter(document -> isReady(context, document)).count();
        Html groups = documents.stream().map(Documents.Document::group).distinct().map(group -> Html.of("<h3 class=\"wizard-group\">%s</h3><ul class=\"doc-list\">%s</ul>",
                group, documents.stream().filter(document -> document.group().equals(group)).map(document -> documentRow(context, document)).collect(Html.joining())))
                .collect(Html.joining());
        return Html.concat(
                explain("Rassemblez ce que vous pouvez, au format papier ou PDF : rien n'est obligatoire. Une coche verte apparaît toute seule quand "
                        + "l'information est déjà dans Patria ; sinon, cochez « J'ai ce document » ou « Pas concerné ». Astuce : avec vos relevés bancaires "
                        + "et vos contrats, l'import de l'étape Dépenses fixes (prompt IA) remplit beaucoup de lignes d'un coup."),
                Components.card("Documents", null, Html.of("<span class=\"muted\">%s sur %s rassemblés</span>", ready, documents.size()),
                        Html.concat(Components.progress(documents.isEmpty() ? 0 : (int) (ready * 100 / documents.size())), groups)));
    }

    private static boolean isReady(WizardController.Context context, Documents.Document document) {
        return document.auto().test(context.snapshot()) || context.documents().containsKey(document.id());
    }

    private static Html documentRow(WizardController.Context context, Documents.Document document) {
        boolean auto = document.auto().test(context.snapshot());
        WizardRepository.DocumentStatus status = context.documents().get(document.id());
        String css = auto || status == WizardRepository.DocumentStatus.HAVE ? "have" : status == WizardRepository.DocumentStatus.NOT_CONCERNED ? "na" : "missing";
        String mark = css.equals("have") ? "✓" : css.equals("na") ? "–" : "";
        Html actions;
        if (auto) {
            actions = Html.raw("<span class=\"badge paid\">Déjà dans Patria</span>");
        } else if (status != null) {
            actions = Html.concat(Html.of("<span class=\"badge%s\">%s</span>", status == WizardRepository.DocumentStatus.HAVE ? " paid" : "",
                    status == WizardRepository.DocumentStatus.HAVE ? "Rassemblé" : "Pas concerné"), docButton(context, document, "", "Annuler", "quiet"));
        } else {
            actions = Html.concat(docButton(context, document, "HAVE", "J'ai ce document", "secondary"), docButton(context, document, "NOT_CONCERNED", "Pas concerné", "quiet"));
        }
        return Html.of("<li class=\"doc %s\" id=\"doc-%s\"><span class=\"doc-mark\" aria-hidden=\"true\">%s</span><div><b>%s</b><span class=\"help\">%s</span></div>"
                + "<div class=\"doc-actions\">%s</div></li>", css, document.id(), mark, document.label(), document.where(), actions);
    }

    private static Html docButton(WizardController.Context context, Documents.Document document, String status, String label, String style) {
        return Html.of("<form method=\"post\" action=\"/assistant/documents/coche\">%s%s<input type=\"hidden\" name=\"document\" value=\"%s\">"
                        + "<input type=\"hidden\" name=\"status\" value=\"%s\"><button class=\"%s\" type=\"submit\">%s</button></form>",
                Components.csrfField(context.csrf()), modeField(context), document.id(), status, style, label);
    }

    // ---- 2. Membres ----

    private static Html members(WizardController.Context context, Map<String, String> values) {
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
                        + "Administration → Utilisateurs."),
                Components.card("Membres du foyer", people.size(), Html.EMPTY, Html.concat(list, add)));
    }

    // ---- 3. Revenus ----

    private static Html incomes(WizardController.Context context, Map<String, String> values) {
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

    // ---- Listes a cocher (logement, depenses fixes, impots) ----

    private static Html checklist(WizardController.Context context, String action, List<Checklist.Group> groups, boolean custom, Map<String, String> values, String submit) {
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

    private static Html checkRow(WizardController.Context context, Checklist.Item item, Map<String, String> values) {
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
    private static String owner(WizardController.Context context, BudgetItem item) {
        List<Person> people = context.snapshot().people();
        if (people.size() < 2) return "";
        String text = Shares.describe(context.shares().getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of()), people);
        return text.isEmpty() ? "" : " · " + text;
    }

    private static Html customRow(WizardController.Context context, String id, Map<String, String> values) {
        return Html.of("<div class=\"check-row custom\"><div class=\"check-label\"><input name=\"label_%s\" value=\"%s\" maxlength=\"100\" placeholder=\"Libellé\" aria-label=\"Libellé\">"
                        + "<select name=\"category_%s\" aria-label=\"Catégorie\">%s</select></div>%s</div>",
                id, values.getOrDefault("label_" + id, ""), id, Category.selectable().stream().map(category -> Html.of("<option value=\"%s\"%s>%s</option>", category.name(),
                        category.name().equals(values.getOrDefault("category_" + id, Category.OTHER.name())) ? Html.raw(" selected") : Html.EMPTY, category.label())).collect(Html.joining()),
                rowFields(context, id, Frequency.MONTHLY, values));
    }

    /** Montant, frequence, premier paiement et attribution d'une ligne. */
    private static Html rowFields(WizardController.Context context, String id, Frequency frequency, Map<String, String> values) {
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

    // ---- 4. Logement ----

    private static Html housing(WizardController.Context context, Map<String, String> values) {
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

    // ---- 5. Biens ----

    private static Html properties(WizardController.Context context, Map<String, String> values) {
        List<Property> properties = context.snapshot().properties();
        Html list = properties.isEmpty() ? Html.raw("<p class=\"empty\">Aucun bien pour l'instant.</p>")
                : Html.of("<ul class=\"done-list\">%s</ul>", properties.stream().map(property -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s · %s%s</li>",
                property.name(), property.kind().label().toLowerCase(java.util.Locale.FRENCH), property.commune().isBlank() ? "" : " · " + property.commune())).collect(Html.joining()));
        Map<String, String> defaults = values.isEmpty() ? Map.of("purchase_date", context.today().toString(), "estimate_date", context.today().toString(), "sale_fees_pct", "8") : values;
        Html add = form(context, "/assistant/biens/ajouter", false, Components.fields(3,
                Components.input(Input.of("Nom du bien", "name", Field.TEXT).required().maxLength(100).placeholder("Appartement Lyon"), defaults),
                Components.select("Type", "kind", Arrays.stream(PropertyKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(),
                        defaults.getOrDefault("kind", PropertyKind.APARTMENT.name())),
                Components.input(Input.of("Commune", "commune", Field.TEXT).maxLength(100).help("Sert au prix du marché (ventes du secteur)."), defaults),
                Components.input(Input.of("Adresse", "address", Field.TEXT).maxLength(200).help("Facultative : affine le prix du marché."), defaults),
                Components.input(Input.of("Surface (m²)", "area", Field.NUMBER), defaults),
                Components.input(Input.of("Coût d'acquisition (€)", "purchase", Field.MONEY).help("Prix + frais de notaire + travaux, selon l'acte."), defaults),
                Components.input(Input.of("Date d'acquisition", "purchase_date", Field.DATE).required(), defaults),
                Components.input(Input.of("Estimation prudente (€)", "estimate", Field.MONEY).help("Ce que vous pensez pouvoir en tirer au minimum."), defaults),
                Components.input(Input.of("Estimation optimiste (€)", "estimate_high", Field.MONEY), defaults),
                Components.input(Input.of("Date de l'estimation", "estimate_date", Field.DATE).required(), defaults),
                Components.input(Input.of("Frais de vente et fiscalité (%)", "sale_fees_pct", Field.NUMBER).help("8 % par défaut ; environ 5 % pour une résidence principale."), defaults)),
                "Ajouter ce bien");
        return Html.concat(explain("Un bien par logement possédé : résidence principale, logement loué, résidence secondaire. Les montants viennent de l'acte "
                        + "d'achat ; une estimation suffit pour la valeur (agence, sites d'estimation), Patria l'actualise ensuite avec les prix du département."),
                Components.card("Biens", properties.size(), Html.EMPTY, Html.concat(list, add)));
    }

    // ---- 6. Prets ----

    private static Html loans(WizardController.Context context) {
        List<Property> properties = context.snapshot().properties();
        if (properties.isEmpty()) {
            return Html.concat(explain("Les prêts se rattachent à un bien."),
                    Components.card("Prêts", null, Html.EMPTY, Html.raw("<p class=\"empty\">Aucun bien : ajoutez-en un à l'étape Biens immobiliers, ou passez cette étape.</p>")));
        }
        Html cards = properties.stream().map(property -> {
            List<fr.patria.loan.Loan> loans = context.snapshot().loans().stream().filter(loan -> loan.propertyId() == property.id()).toList();
            Html list = loans.isEmpty() ? Html.raw("<p class=\"muted\">Aucun prêt importé pour ce bien.</p>")
                    : Html.of("<ul class=\"done-list\">%s</ul>", loans.stream().map(loan -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span><a href=\"/pret/%s\">%s</a></li>",
                    loan.id(), loan.name())).collect(Html.joining()));
            Html add = form(context, "/assistant/prets/" + property.id(), true, Components.fields(2,
                    Components.input(Input.of("Nom du prêt", "name", Field.TEXT).required().maxLength(100).placeholder("Prêt principal, prêt à taux zéro…"), Map.of()),
                    Components.fileInput("Échéancier CSV", "csv")), "Importer ce prêt");
            return Html.of("<section class=\"card section\" id=\"bien-%s\"><div class=\"section-title\"><h2>%s</h2></div>%s%s%s</section>",
                    property.id(), property.name(), list, Components.llmPrompt("prompt-pret-" + property.id(), CsvScheduleParser.llmPrompt()), add);
        }).collect(Html.joining());
        return Html.concat(explain("Un prêt = un fichier d'échéancier CSV, préparé à la main ou avec une IA facultative. Avant de lui joindre le tableau "
                + "d'amortissement, caviardez et vérifiez une copie avec l'outil ci-dessous ; n'envoyez pas l'original. Vérifiez le CSV obtenu avant import. Un prêt à taux zéro ou un "
                + "prêt complémentaire s'importe comme un prêt à part."), cards);
    }

    // ---- 7. Charges et loyers des biens ----

    private static Html charges(WizardController.Context context, Map<String, String> values) {
        List<Property> properties = context.snapshot().properties();
        if (properties.isEmpty()) {
            return Html.concat(explain("Les charges et loyers se rattachent à un bien."),
                    Components.card("Charges", null, Html.EMPTY, Html.raw("<p class=\"empty\">Aucun bien : ajoutez-en un à l'étape Biens immobiliers, ou passez cette étape.</p>")));
        }
        Html cards = properties.stream().map(property -> {
            Html list = Html.of("<ul class=\"done-list\">%s%s</ul>",
                    context.snapshot().charges().stream().filter(charge -> charge.propertyId() == property.id())
                            .map(charge -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s %s · %s</li>", charge.kind().label(), charge.year(), euroWhole(charge.amount())))
                            .collect(Html.joining()),
                    context.snapshot().rents().stream().filter(rent -> rent.propertyId() == property.id())
                            .map(rent -> Html.of("<li><span class=\"ok\" aria-hidden=\"true\">✓</span>%s depuis le %s · %s/mois</li>", rent.kind().label(), Format.date(rent.start()),
                                    euroWhole(rent.monthly()))).collect(Html.joining()));
            Html charge = form(context, "/assistant/charges/" + property.id(), false, Components.fields(3,
                    Components.select("Charge", "kind", Arrays.stream(ExpenseKind.values()).map(kind -> new Option(kind.name(), kind.label())).toList(), ExpenseKind.PROPERTY_TAX.name()),
                    Components.input(Input.of("Année", "year", Field.NUMBER).required(), Map.of("year", String.valueOf(context.today().getYear()))),
                    Components.input(Input.of("Montant annuel (€)", "amount", Field.MONEY).required(), Map.of())), "Ajouter cette charge");
            Html occupancy = form(context, "/assistant/occupation/" + property.id(), false, Components.fields(3,
                    Components.select("Occupation", "kind", Arrays.stream(Occupancy.values()).map(kind -> new Option(kind.name(), kind.label())).toList(), Occupancy.RENTAL.name()),
                    Components.input(Input.of("Loyer mensuel (€)", "monthly", Field.MONEY).required().help("Loué : loyer hors charges perçu. Résidence principale : loyer d'un logement équivalent."), Map.of()),
                    Components.input(Input.of("Depuis le", "start", Field.DATE).required(), Map.of("start", property.purchaseDate().toString()))), "Ajouter cette occupation");
            return Html.of("<section class=\"card section\" id=\"bien-%s\"><div class=\"section-title\"><h2>%s</h2></div>%s<h3 class=\"wizard-group\">Charges</h3>%s"
                    + "<h3 class=\"wizard-group\">Occupation</h3>%s</section>", property.id(), property.name(), list, charge, occupancy);
        }).collect(Html.joining());
        return Html.concat(explain("Pour chaque bien : la dernière taxe foncière (un seul avis suffit, les autres années sont estimées), les charges de "
                + "copropriété et l'assurance (montant annuel). Puis son occupation : loué (loyer perçu, qui entre dans le budget) ou résidence principale "
                + "(loyer économisé, pour calculer l'enrichissement réel). La taxe foncière peut être mensualisée depuis Administration → le bien → Charges."), cards);
    }

    // ---- 8. Depenses fixes ----

    private static Html fixed(WizardController.Context context, Map<String, String> values) {
        Html quick = Html.of("""
                <details class="help-block"><summary>Plus rapide : tout importer avec vos relevés bancaires</summary>
                <p class="muted">L'IA est facultative. Si vous l'utilisez, caviardez et vérifiez d'abord les copies de vos relevés et contrats. Joignez uniquement ces copies avec le prompt : elle renvoie un fichier
                à importer dans <a href="/administration/depenses">Administration → Dépenses → Importer</a>. Revenez ensuite ici : les lignes importées apparaîtront cochées.</p>%s</details>""",
                Components.llmPrompt("prompt-depenses-assistant", ExpenseCsv.llmPrompt()));
        return Html.concat(explain("Une dépense fixe revient au même rythme et presque au même montant : abonnements, énergie, assurances, garde d'enfants, "
                        + "transports. Une dépense variable (courses, loisirs) se saisit avec un budget moyen. Cochez ce que vous payez, indiquez le montant "
                        + "d'un paiement et sa fréquence : Patria calcule les 12 prochains mois. Inutile de saisir ce que Patria reprend de l'immobilier "
                        + "(mensualités des prêts immobiliers, taxe foncière, copropriété, assurance d'un bien possédé)."),
                Components.card("Dépenses fixes", null, Html.EMPTY, Html.concat(quick,
                        checklist(context, "/assistant/depenses/ajouter", Checklist.FIXED, true, values, "Ajouter les dépenses cochées"))));
    }

    // ---- 9. Impots ----

    private static Html taxes(WizardController.Context context, Map<String, String> values) {
        return Html.concat(explain("Sur l'avis d'impôt (impots.gouv.fr → Documents), le cadre « Votre prélèvement à la source » donne le taux et le montant "
                        + "mensuel. Avec deux salaires, saisissez un prélèvement par personne (« Pour qui »). Un solde à payer se saisit en ponctuel à sa date."),
                Components.card(Checklist.TAXES.title(), null, Html.EMPTY, checklist(context, "/assistant/impots/ajouter", List.of(Checklist.TAXES), false, values,
                        "Ajouter ces impôts")));
    }

    // ---- 10. Virements ----

    private static Html transfers(WizardController.Context context, Map<String, String> values) {
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

    private static String name(List<Person> people, long id) {
        return people.stream().filter(person -> person.id() == id).map(Person::name).findFirst().orElse("?");
    }

    // ---- 11. Repartition ----

    private static Html shares(WizardController.Context context) {
        List<Person> people = context.snapshot().people();
        if (people.size() < 2 || context.snapshot().properties().isEmpty()) {
            return Html.concat(explain("La répartition dit quelle part de chaque bien et de chaque prêt revient à chaque membre."),
                    Components.card("Répartition", null, Html.EMPTY, Html.raw("<p class=\"empty\">Rien à répartir : il faut au moins deux membres et un bien. Passez cette étape.</p>")));
        }
        Html rows = context.snapshot().properties().stream().map(property -> Html.of("<li><b>%s</b> : %s</li>", property.name(),
                Shares.describe(context.shares().getOrDefault(new Shares.Key(Shares.Subject.PROPERTY, property.id()), Map.of()), people))).collect(Html.joining());
        return Html.concat(explain("Par défaut, chaque bien et chaque prêt est à parts égales entre les personnes (le compte joint n'y prend part que si on lui "
                        + "donne un pourcentage). Si un bien appartient surtout à l'un, ou si le compte joint paie la taxe foncière, ajustez-le."),
                Components.card("Répartition actuelle", null, Components.secondaryButton("/administration/repartition", "Ajuster la répartition"),
                        Html.of("<ul class=\"plain-list\">%s</ul><p class=\"muted\">La page Répartition s'ouvre dans l'administration ; revenez ensuite ici pour terminer l'étape.</p>", rows)));
    }

    // ---- 12. Recapitulatif ----

    private static Html summary(WizardController.Context context) {
        Snapshot data = context.snapshot();
        List<Step> steps = context.steps().stream().filter(step -> step != Step.SUMMARY).toList();
        Html rows = steps.stream().map(step -> Html.of("<tr><td><a href=\"%s\">%s</a></td><td><span class=\"badge%s\">%s</span></td></tr>", step.path(), step.title(),
                css(context, step).equals("done") ? " paid" : "", state(context, step))).collect(Html.joining());
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        long ready = documents.stream().filter(document -> isReady(context, document)).count();
        List<String[]> counts = new java.util.ArrayList<>();
        if (context.admin()) counts.add(new String[]{"Membres", String.valueOf(data.people().size())});
        counts.add(new String[]{"Revenus", String.valueOf(data.incomes().size())});
        counts.add(new String[]{"Dépenses", String.valueOf(data.items().size())});
        if (context.admin()) {
            counts.add(new String[]{"Biens", String.valueOf(data.properties().size())});
            counts.add(new String[]{"Prêts", String.valueOf(data.loans().size())});
            counts.add(new String[]{"Charges de biens", String.valueOf(data.charges().size())});
        }
        counts.add(new String[]{"Virements", String.valueOf(data.transfers().size())});
        counts.add(new String[]{"Documents rassemblés", ready + " sur " + documents.size()});
        Html figures = counts.stream().map(count -> Html.of("<div><dt>%s</dt><dd>%s</dd></div>", count[0], count[1])).collect(Html.joining());
        boolean complete = steps.stream().allMatch(context.progress()::containsKey);
        Html actions = Html.of("<div class=\"heading-actions\">%s%s%s</div>", Components.button("/", "Voir le prévisionnel"),
                complete ? Html.EMPTY : Components.secondaryButton(context.resume().path(), "Reprendre les étapes à faire"),
                Components.secondaryButton(Step.DOCUMENTS.path() + "?mode=" + WizardController.REVIEW, "Tout revoir"));
        return Html.concat(
                complete ? Html.raw("<p class=\"notice success\">Toutes les étapes sont faites ou passées. Le prévisionnel tient compte de tout ce que vous avez saisi.</p>")
                        : Html.raw("<p class=\"notice warning\">Certaines étapes restent à faire : vous pouvez les reprendre quand vous voulez.</p>"),
                Components.card("Ce qui est saisi", null, Html.EMPTY, Html.of("<dl class=\"calc-grid\">%s</dl>", figures)),
                Components.card("Étapes", null, Html.EMPTY, Html.concat(Components.table(List.of("Étape", "État"), rows, false, false), actions)));
    }

    // ---- Revue annuelle ----

    /** Bandeau du Previsionnel : revue conseillee, et ce qu'il faut verifier ce mois-ci. */
    /** Encadre jaune du Previsionnel : configuration terminee a x %, avec « Terminer les imports » et « Ignorer ». */
    static Html progress(int percent, int finished, int total, Step next, String csrf) {
        return Html.of("""
                <section class="notice warning setup-progress" aria-label="Configuration du foyer"><div class="setup-progress-text">\
                <b>Configuration terminée à %s %</b><span>%s étape%s sur %s faite%s ou passée%s dans le Guide de saisie. Prochaine : %s.</span>\
                <div class="progress-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="%s"><i style="width:%s%"></i></div></div>\
                <div class="setup-progress-actions"><a class="button" href="/assistant">Terminer les imports</a>\
                <form method="post" action="/assistant/encadre/ignorer">%s<button class="quiet" type="submit" title="Ne plus afficher cet encadré (le guide reste dans l'en-tête)">Ignorer</button></form></div></section>""",
                percent, finished, finished > 1 ? "s" : "", total, finished > 1 ? "s" : "", finished > 1 ? "s" : "", next.title(), percent, percent,
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

    private static boolean active(java.time.LocalDate end, WizardController.Context context) {
        return end == null || !end.isBefore(context.today());
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Une ligne de la revue : montant actuel, nouveau montant, date d'effet, « Mettre à jour » ou « Arrêtée ». */
    private static Html reviewRow(WizardController.Context context, String action, String label, String detail, java.math.BigDecimal amount, Frequency frequency,
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

    private static Html propertyReview(WizardController.Context context, Property property) {
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
