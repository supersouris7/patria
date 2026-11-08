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
        // Un ecran de document a ses propres boutons Precedent / Suivant ; l'ecran de fin des documents garde ceux de l'etape.
        boolean documentScreen = step == Step.DOCUMENTS && currentDocument(context) != null;
        Html bar = Html.of("<div class=\"wizard-bar\">%s<details class=\"wizard-plan\"><summary>Plan du guide</summary><ol class=\"wizard-plan-steps\">%s</ol></details></div>",
                Components.progress(context.steps().isEmpty() ? 0 : (number - 1) * 100 / context.steps().size()), stepper(context, step));
        Html content = Html.concat(bar, heading, Components.notice(notice), body, step == Step.SUMMARY || documentScreen ? Html.EMPTY : footer(context, step));
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
     * Phase 1 du guide : les documents, un par ecran. « Je l'ai » ouvre ici meme l'import de ce document (prompt,
     * reponse de l'IA a coller, apercu a cocher) ; « Pas sous la main » ou « Pas concerne » passe au suivant. Aucun
     * lien ne saute vers une autre etape : les categories viennent ensuite, dans l'ordre.
     */
    static Html documents(WizardController.Context context) {
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        long ready = documents.stream().filter(document -> isReady(context, document)).count();
        Documents.Document current = currentDocument(context);
        int percent = documents.isEmpty() ? 100 : (int) (ready * 100 / documents.size());
        Html progress = Html.of("<div class=\"doc-progress\"><span>%s</span>%s</div>",
                current == null ? ready + " document" + (ready > 1 ? "s" : "") + " sur " + documents.size() + " rassemblé" + (ready > 1 ? "s" : "")
                        : "Document " + (documents.indexOf(current) + 1) + " sur " + documents.size() + " · " + current.group(),
                Components.progress(current == null ? 100 : (documents.indexOf(current) * 100 / documents.size())));
        Html plan = Html.of("<details class=\"wizard-plan\"><summary>Liste des documents (%s sur %s)</summary><ol class=\"doc-plan\">%s</ol></details>", ready, documents.size(),
                documents.stream().map(document -> Html.of("<li class=\"%s\"><a href=\"/assistant/documents?doc=%s\">%s</a></li>", isReady(context, document) ? "have" : "",
                        document.id(), document.label())).collect(Html.joining()));
        if (current == null) {
            return Html.concat(progress, Components.card("Documents terminés", null, Html.EMPTY, Html.of("""
                    <p>%s document%s sur %s rassemblé%s ou déjà dans Patria. La suite du guide reprend chaque catégorie (revenus, dépenses, prêts, épargne…) :
                    vous y retrouvez ce qui a été importé, à vérifier, compléter ou supprimer.</p>""", ready, ready > 1 ? "s" : "", documents.size(), ready > 1 ? "s" : "")),
                    plan);
        }
        boolean auto = current.auto().test(context.snapshot());
        WizardRepository.DocumentStatus status = context.documents().get(current.id());
        boolean have = auto || status == WizardRepository.DocumentStatus.HAVE;
        String back = "/assistant/documents?doc=" + current.id();
        Html state = auto ? Html.raw("<span class=\"badge paid\">Déjà dans Patria</span>")
                : status == WizardRepository.DocumentStatus.HAVE ? Html.raw("<span class=\"badge paid\">Je l'ai</span>")
                : status == WizardRepository.DocumentStatus.NOT_CONCERNED ? Html.raw("<span class=\"badge\">Pas concerné</span>") : Html.EMPTY;
        Html choices = Html.of("<div class=\"doc-choices\">%s%s%s</div>",
                docButton(context, current, "HAVE", have ? "Importer à nouveau" : "Je l'ai", have ? "secondary" : ""),
                docButton(context, current, "", "Pas sous la main", "secondary"),
                docButton(context, current, "NOT_CONCERNED", "Pas concerné", "quiet"));
        Html importArea = have ? Html.of("<section class=\"doc-import\" id=\"import\">%s</section>", importFor(context, current, back)) : Html.EMPTY;
        int index = documents.indexOf(current);
        Html previous = index == 0 ? Html.raw("<a class=\"button secondary\" href=\"/assistant\">← Guide</a>")
                : Html.of("<a class=\"button secondary\" href=\"/assistant/documents?doc=%s\">← Précédent</a>", documents.get(index - 1).id());
        Html next = Html.of("<a class=\"button\" href=\"/assistant/documents?doc=%s\">Suivant →</a>", Documents.after(current.id(), documents));
        return Html.concat(progress,
                Html.of("""
                        <section class="card doc-screen"><p class="eyebrow">%s</p><h2>%s %s</h2><p class="help">%s</p>%s%s</section>""",
                        current.group(), current.label(), state, current.where(), choices, importArea),
                Html.of("<div class=\"wizard-footer\">%s<div class=\"wizard-next\">%s</div></div>", previous, next), plan);
    }

    /** Document affiche : celui demande ({@code ?doc=}), sinon le premier a traiter ; {@code null} une fois au bout. */
    static Documents.Document currentDocument(WizardController.Context context) {
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        String wanted = context.document();
        if ("fin".equals(wanted)) return null;
        return documents.stream().filter(document -> document.id().equals(wanted)).findFirst()
                .orElse(documents.stream().filter(document -> !isReady(context, document)).findFirst().orElse(null));
    }

    /** Import propre a un document, dans son ecran : retour au meme document apres l'apercu. */
    static Html importFor(WizardController.Context context, Documents.Document document, String back) {
        String kind = Documents.importKind(document.id());
        Html warning = Components.localAi() ? Html.raw("<p class=\"notice success\"><b>IA locale</b> : déposez le document, il est analysé sur votre serveur.</p>")
                : Html.EMPTY;
        if (kind == null) {
            Step step = Documents.next(document.id());
            return Html.of("<p>Ce document se saisit à l'étape « %s », plus loin dans le guide : gardez-le sous la main.</p>",
                    step == null ? "Récapitulatif" : step.title());
        }
        return switch (kind) {
            case "revenus" -> Html.concat(warning, fr.patria.household.HouseholdPages.importForm(context.visibility().people(), back, context.csrf()));
            case "bourse" -> Html.concat(warning, context.brokerImport().apply(back));
            case "echeancier" -> loanImport(context, back);
            default -> Html.concat(warning, Components.llmPrompt("prompt-doc-" + document.id(), fr.patria.budget.ExpenseCsv.llmPrompt()), Html.of("""
                    <form method="post" action="/administration/depenses/import" class="stack" enctype="multipart/form-data">%s<input type="hidden" name="retour" value="%s">%s
                    <label>Réponse de l'IA<textarea name="texte" rows="10"></textarea></label>
                    <div class="form-actions"><button type="submit">Voir l'aperçu</button></div></form>""",
                    Components.csrfField(context.csrf()), back, Components.localAiInput("depenses")));
        };
    }

    /** Echeancier d'un pret : une zone par bien ; sans bien, il se saisit a l'etape Biens puis Prets. */
    private static Html loanImport(WizardController.Context context, String back) {
        if (context.snapshot().properties().isEmpty()) {
            return Html.raw("<p>Le prêt se rattache à un bien : créez le bien à l'étape « Biens immobiliers », puis importez l'échéancier à l'étape « Prêts ».</p>");
        }
        Html forms = context.snapshot().properties().stream().map(property -> Html.of("""
                <form method="post" action="/assistant/prets/%s" class="stack" enctype="multipart/form-data">%s<input type="hidden" name="retour" value="%s">
                <h3>%s</h3><label>Nom du prêt<input name="name" maxlength="100" required placeholder="Prêt principal, prêt à taux zéro…"></label>%s
                <label>Réponse de l'IA (lignes de l'échéancier)<textarea name="texte" rows="10"></textarea></label>
                <div class="form-actions"><button type="submit">Importer ce prêt</button></div></form>""",
                property.id(), Components.csrfField(context.csrf()), back, property.name(), Components.localAiInput("echeancier"))).collect(Html.joining());
        return Html.concat(Components.llmPrompt("prompt-doc-pret", fr.patria.loan.CsvScheduleParser.llmPrompt()), forms);
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
