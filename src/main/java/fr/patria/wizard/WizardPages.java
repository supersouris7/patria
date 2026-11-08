package fr.patria.wizard;

import static fr.patria.wizard.WizardBudgetPages.*;
import static fr.patria.wizard.WizardEstatePages.*;

import fr.patria.household.Person;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
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
                Components.card("Les étapes", null, Html.of("<span class=\"muted\">%s sur %s · %s %%</span>".replace("%%", "%"), handled, steps.size(), percent),
                        Html.concat(Components.progress(percent), Html.of("<ol class=\"wizard-overview\">%s</ol>", list),
                                Html.of("<form method=\"post\" action=\"/assistant/recommencer\" class=\"wizard-reset\" data-confirm=\"%s\">%s"
                                                + "<button class=\"quiet\" type=\"submit\">Recommencer à zéro</button><span class=\"muted\">efface la progression, pas les données saisies</span></form>",
                                         "Effacer la progression du guide ? Les revenus, dépenses et biens saisis restent.", Components.csrfField(context.csrf())))),
                Components.disclosure("Comment ça marche", intro, false));
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
            case SAVINGS -> Html.concat(explain("Saisissez le solde de chaque livret, assurance-vie, PER, PEA ou compte-titres. Un versement programmé fait avancer le solde "
                    + "tout seul et apparaît dans le budget. Pour la bourse, importez les lignes depuis l'export de votre courtier (Trade Republic, Boursorama…) "
                    + "avec le prompt IA, puis complétez ou supprimez à la main."), context.savings().get());
            case HEALTH -> healthScope(context);
            case SUMMARY -> summary(context);
        };
        int number = context.steps().indexOf(step) + 1;
        Html heading = Components.heading("Guide · étape " + number + " sur " + context.steps().size() + (context.review() ? " · revue" : ""),
                step.title(), step.summary(), Html.EMPTY);
        Html bar = Html.of("<div class=\"wizard-bar\">%s<details class=\"wizard-plan\"><summary>Plan du guide</summary><ol class=\"wizard-plan-steps\">%s</ol></details></div>",
                Components.progress(context.steps().isEmpty() ? 0 : (number - 1) * 100 / context.steps().size()), stepper(context, step));
        Html content = Html.concat(bar, heading, Components.notice(notice), toSort(context, step), body, step == Step.SUMMARY ? Html.EMPTY : footer(context, step));
        Html layout = Html.of("<div class=\"wizard wizard-linear\"><div class=\"wizard-body\">%s</div></div>", content);
        return Layout.page(step.title() + " · Guide de saisie · Patria", Layout.Section.ASSISTANT, context.csrf(), layout);
    }

    static Html stepper(WizardController.Context context, Step current) {
        return context.steps().stream().map(step -> Html.of("<li class=\"%s%s\"><a href=\"%s%s\"%s><span class=\"wizard-dot\" aria-hidden=\"true\">%s</span>%s</a></li>",
                css(context, step), step == current ? " current" : "", step.path(), context.mode(),
                Html.raw(step == current ? " aria-current=\"step\"" : ""), dot(context, step), step.title())).collect(Html.joining());
    }

    /** Bas de page : precedent, passer, etape terminee. */
    static Html footer(WizardController.Context context, Step step) {
        Step previous = context.previous(step);
        Html back = previous == null ? Html.raw("<span></span>") : Html.of("<a class=\"button secondary\" href=\"%s%s\">← %s</a>", previous.path(), context.mode(), previous.title());
        return Html.of("""
                <div class="wizard-footer">%s<div class="wizard-next">\
                <form method="post" action="%s/passer">%s%s<button class="quiet" type="submit">Passer cette étape</button></form>\
                <form method="post" action="%s/terminer">%s%s<button type="submit">Suivant →</button></form></div></div>""",
                back, step.path(), Components.csrfField(context.csrf()), modeField(context), step.path(), Components.csrfField(context.csrf()), modeField(context));
    }

    static Html modeField(WizardController.Context context) {
        return context.review() ? Html.of("<input type=\"hidden\" name=\"mode\" value=\"%s\">", WizardController.REVIEW) : Html.EMPTY;
    }

    static Html form(WizardController.Context context, String action, boolean multipart, Html body, String submit) {
        return Components.form(action, context.csrf(), multipart, Html.concat(modeField(context), body), submit);
    }

    static String css(WizardController.Context context, Step step) {
        WizardRepository.Status status = context.progress().get(step);
        return status == WizardRepository.Status.DONE ? "done" : status == WizardRepository.Status.SKIPPED ? "skipped" : "todo";
    }

    static String dot(WizardController.Context context, Step step) {
        WizardRepository.Status status = context.progress().get(step);
        return status == WizardRepository.Status.DONE ? "✓" : status == WizardRepository.Status.SKIPPED ? "↷" : String.valueOf(context.steps().indexOf(step) + 1);
    }

    static String state(WizardController.Context context, Step step) {
        WizardRepository.Status status = context.progress().get(step);
        return status == WizardRepository.Status.DONE ? "Fait" : status == WizardRepository.Status.SKIPPED ? "Passé" : "À faire";
    }

    static Html explain(String text) {
        return Html.of("<p class=\"wizard-explain\">%s</p>", text);
    }

    // ---- 1. Documents ----

    /**
     * Phase 1 du guide : reunir les documents, puis les donner tous a l'IA avec un prompt universel. Sa reponse rejoint
     * la pile « A trier » ; rien n'entre dans le budget. Le tri se fait ensuite, categorie par categorie (phase 2).
     */
    static Html documents(WizardController.Context context) {
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        long ready = documents.stream().filter(document -> isReady(context, document)).count();
        Html groups = documents.stream().map(Documents.Document::group).distinct().map(group -> Html.of("<h3 class=\"wizard-group\">%s</h3><ul class=\"doc-list\">%s</ul>",
                group, documents.stream().filter(document -> document.group().equals(group)).map(document -> documentRow(context, document)).collect(Html.joining())))
                .collect(Html.joining());
        Map<ImportInbox.Type, Long> pile = context.inbox().stream().collect(java.util.stream.Collectors.groupingBy(ImportInbox.Item::type,
                () -> new java.util.EnumMap<>(ImportInbox.Type.class), java.util.stream.Collectors.counting()));
        Html pileCard = Components.card("Pile « À trier »", context.inbox().size(), Html.EMPTY, pile.isEmpty()
                ? Html.raw("<p class=\"muted\">Vide pour l'instant. Après l'envoi de la réponse de l'IA, ses lignes attendent ici que vous les triiez.</p>")
                : Html.of("<ul class=\"pile\">%s</ul><p class=\"muted\">Rien n'est encore dans votre budget : chaque ligne se valide ou se refuse à l'étape indiquée, avec « Suivant ».</p>",
                pile.entrySet().stream().map(entry -> Html.of("<li><b>%s</b> %s <span class=\"muted\">→ étape « %s »</span></li>", entry.getValue(),
                        entry.getKey().label().toLowerCase(java.util.Locale.FRENCH), entry.getKey().step().title())).collect(Html.joining())));
        Html give = Html.of("""
                <form method="post" action="/assistant/documents/importer" class="stack" enctype="multipart/form-data">%s%s%s
                <label>Réponse de l'IA (toutes les lignes, vous pouvez coller en plusieurs fois)<textarea name="texte" rows="12"
                placeholder="REVENU;Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;"></textarea></label>
                <div class="form-actions"><button type="submit">Ajouter à la pile « À trier »</button></div></form>""",
                Components.csrfField(context.csrf()), modeField(context),
                Components.localAiInput("tout|" + context.visibility().people().stream().map(person -> person.name() + (person.isAccount() ? "*" : ""))
                        .collect(java.util.stream.Collectors.joining(","))));
        return Html.concat(
                explain("Deux temps. D'abord, donnez tous vos documents à l'IA d'un coup : tout ce qu'elle trouve (même en trop) attend dans la pile « À trier ». "
                        + "Ensuite, « Suivant » passe chaque catégorie : vous validez ou refusez ses lignes, puis complétez à la main."),
                Components.card("1. Réunissez vos documents", null, Html.of("<span class=\"muted\">%s sur %s</span>", ready, documents.size()),
                        Html.concat(Components.progress(documents.isEmpty() ? 0 : (int) (ready * 100 / documents.size())),
                                Html.of("<details class=\"doc-checklist\"%s><summary>Liste des documents utiles</summary>%s</details>",
                                        Html.raw(context.inbox().isEmpty() ? " open" : ""), groups))),
                Html.of("<div id=\"import\">%s</div>", Components.card("2. Donnez-les tous à l'IA", null, Html.EMPTY,
                        Html.concat(Components.llmPrompt("prompt-universel", ImportInbox.prompt(context.visibility().people())), give))),
                pileCard);
    }

    /** Un document de la liste : coche automatique, « J'ai ce document » ou « Pas concerne », sans quitter l'etape. */
    static Html documentRow(WizardController.Context context, Documents.Document document) {
        boolean auto = document.auto().test(context.snapshot());
        WizardRepository.DocumentStatus status = context.documents().get(document.id());
        String css = auto || status == WizardRepository.DocumentStatus.HAVE ? "have" : status == WizardRepository.DocumentStatus.NOT_CONCERNED ? "na" : "missing";
        Html actions = auto ? Html.raw("<span class=\"badge paid\">Déjà dans Patria</span>")
                : status != null ? Html.concat(Html.of("<span class=\"badge%s\">%s</span>", status == WizardRepository.DocumentStatus.HAVE ? " paid" : "",
                status == WizardRepository.DocumentStatus.HAVE ? "Je l'ai" : "Pas concerné"), docButton(context, document, "", "Annuler", "quiet"))
                : Html.concat(docButton(context, document, "HAVE", "Je l'ai", "secondary"), docButton(context, document, "NOT_CONCERNED", "Pas concerné", "quiet"));
        return Html.of("<li class=\"doc %s\" id=\"doc-%s\"><span class=\"doc-mark\" aria-hidden=\"true\">%s</span><div><b>%s</b><span class=\"help\">%s</span></div>"
                + "<div class=\"doc-actions\">%s</div></li>", css, document.id(), css.equals("have") ? "✓" : css.equals("na") ? "–" : "", document.label(), document.where(), actions);
    }

    /**
     * Phase 2 : les lignes de la pile qui se trient a cette etape (depenses de logement, d'impots ou les autres ;
     * revenus ; virements ; epargne et bourse). Cochees par defaut, sauf doublon possible ou depense resiliee.
     */
    static Html toSort(WizardController.Context context, Step step) {
        List<ImportInbox.Item> items = context.inbox().stream().filter(item -> belongs(item, step)).toList();
        if (items.isEmpty()) return Html.EMPTY;
        List<Person> people = context.visibility().people();
        Html rows = items.stream().map(item -> {
            String what;
            boolean checked = true;
            String group;
            try {
                switch (item.type()) {
                    case DEPENSE -> {
                        ImportInbox.Expense expense = ImportInbox.expense(item.line(), people, context.snapshot().items());
                        what = expense.item().label() + " · " + expense.item().category().label() + " · " + fr.patria.ui.Format.euro(expense.item().amount()) + " "
                                + expense.item().frequency().label().toLowerCase(java.util.Locale.FRENCH)
                                + (expense.person() == null ? "" : " · " + people.stream().filter(p -> p.id() == expense.person()).map(Person::name).findFirst().orElse(""))
                                + (expense.hints().isEmpty() ? "" : " · " + String.join(", ", expense.hints())) + (expense.duplicate() ? " · déjà saisie" : "");
                        checked = !expense.duplicate() && expense.hints().stream().noneMatch(hint -> hint.equals("Doublon possible") || hint.equals("Résilié"));
                        group = expense.item().category().label();
                    }
                    case REVENU -> {
                        fr.patria.household.IncomeCsv.Row row = ImportInbox.income(item.line(), people, context.snapshot().incomes());
                        what = row.person() + " · " + row.income().label() + " · " + fr.patria.ui.Format.euro(row.income().amount()) + " "
                                + row.income().frequency().label().toLowerCase(java.util.Locale.FRENCH) + (row.duplicate() ? " · déjà saisi" : "");
                        checked = !row.duplicate();
                        group = row.income().kind().label();
                    }
                    case VIREMENT -> {
                        ImportInbox.Transfer transfer = ImportInbox.transfer(item.line(), people);
                        what = memberName(people, transfer.from()) + " → " + memberName(people, transfer.to()) + " · " + transfer.label() + " · " + fr.patria.ui.Format.euro(transfer.amount())
                                + " " + transfer.frequency().label().toLowerCase(java.util.Locale.FRENCH);
                        group = "Virements";
                    }
                    case EPARGNE -> {
                        ImportInbox.Account account = ImportInbox.account(item.line(), people);
                        what = account.name() + " (" + account.kind().label() + ") · " + memberName(people, account.person()) + " · solde " + fr.patria.ui.Format.euro(account.balance())
                                + (account.monthly() == null ? "" : " · versement " + fr.patria.ui.Format.euro(account.monthly()) + " par mois");
                        group = "Placements";
                    }
                    default -> {
                        ImportInbox.Position position = ImportInbox.position(item.line());
                        what = position.row().label() + " (" + position.row().code() + ") · " + fr.patria.ui.Format.number(position.row().quantity(), 6) + " parts · "
                                + fr.patria.ui.Format.euro(position.row().cost()) + " · compte « " + position.account() + " »";
                        group = "Bourse";
                    }
                }
            } catch (fr.patria.common.ValidationException exception) {
                what = item.line() + " — " + exception.getMessage();
                checked = false;
                group = "À vérifier";
            }
            return Html.of("""
                    <li><input type="hidden" name="vu_%s" value="on"><label class="check"><input type="checkbox" name="garder_%s" value="on"%s data-import-row \
                    data-groups="c:%s"> %s</label></li>""", item.id(), item.id(), Html.raw(checked ? " checked" : ""), group, what);
        }).collect(Html.joining());
        return Html.of("""
                <section class="card section to-sort" id="a-trier"><div class="section-title"><h2>À trier</h2><span class="badge">%s</span></div>
                <p class="muted">Trouvé par l'IA dans vos documents. Cochez ce qui est juste : « Valider » l'enregistre ; ce qui n'est pas coché est écarté.
                Les montants se corrigent ensuite dans la liste « Déjà saisies ».</p>
                <div class="import-pickers" data-import-pickers><div class="pick-row"><span class="pick-label">Sélection</span>\
                <button type="button" class="pick" data-import-all="on">Tout</button><button type="button" class="pick" data-import-all="off">Rien</button></div>\
                <p class="import-count" data-import-count aria-live="polite"></p></div>
                <form method="post" action="/assistant/tri">%s%s<input type="hidden" name="etape" value="%s"><ul class="plain-list sort-list">%s</ul>
                <div class="form-actions"><button type="submit">Valider la sélection</button></div></form></section>""",
                items.size(), Components.csrfField(context.csrf()), modeField(context), step.path(), rows);
    }

    private static boolean belongs(ImportInbox.Item item, Step step) {
        if (item.type() != ImportInbox.Type.DEPENSE) return item.type().step() == step;
        String category = item.line().split(";", -1).length > 1 ? ImportInbox.normalize(item.line().split(";", -1)[1]) : "";
        boolean housing = category.equals(ImportInbox.normalize(fr.patria.budget.Category.HOUSING.label())) || category.equals("housing");
        boolean taxes = category.equals(ImportInbox.normalize(fr.patria.budget.Category.TAXES.label())) || category.equals("taxes");
        return step == (housing ? Step.HOUSING : taxes ? Step.TAXES : Step.FIXED);
    }

    private static String memberName(List<Person> people, long id) {
        return people.stream().filter(person -> person.id() == id).map(Person::name).findFirst().orElse("?");
    }

    static boolean isReady(WizardController.Context context, Documents.Document document) {
        return document.auto().test(context.snapshot()) || context.documents().containsKey(document.id());
    }

    static Html docButton(WizardController.Context context, Documents.Document document, String status, String label, String style) {
        return Html.of("<form method=\"post\" action=\"/assistant/documents/coche\">%s%s<input type=\"hidden\" name=\"document\" value=\"%s\">"
                        + "<input type=\"hidden\" name=\"status\" value=\"%s\"><button class=\"%s\" type=\"submit\">%s</button></form>",
                Components.csrfField(context.csrf()), modeField(context), document.id(), status, style, label);
    }

    // ---- 3. Revenus ----

    // ---- 4. Logement ----

    // ---- 6. Prets ----

    // ---- 8. Depenses fixes ----

    // ---- 10. Virements ----

    static String name(List<Person> people, long id) {
        return people.stream().filter(person -> person.id() == id).map(Person::name).findFirst().orElse("?");
    }

    // ---- 12. Recapitulatif ----

    static Html summary(WizardController.Context context) {
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


    /** Etape « Bilan de sante » : les comptes pris en compte, cases des membres visibles. */
    static Html healthScope(WizardController.Context context) {
        List<Person> visible = context.visibility().people();
        Html boxes = visible.stream().map(person -> Html.of("<label class=\"check option\"><input type=\"checkbox\" name=\"membre_%s\" value=\"on\"%s><span><b>%s</b>"
                + "<span class=\"help\">%s</span></span></label>", person.id(),
                Html.raw(context.healthScope().isEmpty() || context.healthScope().contains(person.id()) ? " checked" : ""), person.name(),
                person.isAccount() ? "Compte joint : ses dépenses et les virements qu'il reçoit." : "Ses revenus et sa part des dépenses.")).collect(Html.joining());
        return Html.concat(explain("Le bilan de santé analyse vos finances à partir des comptes que vous choisissez ici. Par défaut : le membre que vous "
                        + "représentez et les comptes joints que vous voyez. Ajoutez d'autres membres pour un bilan de tout le foyer ; vous pourrez changer ce choix depuis le Bilan."),
                Components.card("Comptes pris en compte" + (context.healthConfigured() ? "" : " (par défaut)"), null, Html.EMPTY,
                        form(context, "/assistant/bilan/enregistrer", false, Html.of("<div class=\"access-options\">%s</div>", boxes), "Enregistrer et continuer")));
    }
}
