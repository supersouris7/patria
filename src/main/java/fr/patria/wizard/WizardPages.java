package fr.patria.wizard;

import static fr.patria.wizard.WizardBudgetPages.*;
import static fr.patria.wizard.WizardEstatePages.*;

import fr.patria.household.Person;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.ui.RedactionPage;
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
                <form method="post" action="%s/terminer">%s%s<button type="submit">Étape terminée →</button></form></div></div>""",
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

    static Html documents(WizardController.Context context) {
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        long ready = documents.stream().filter(document -> isReady(context, document)).count();
        Html groups = documents.stream().map(Documents.Document::group).distinct().map(group -> Html.of("<h3 class=\"wizard-group\">%s</h3><ul class=\"doc-list\">%s</ul>",
                group, documents.stream().filter(document -> document.group().equals(group)).map(document -> documentRow(context, document)).collect(Html.joining())))
                .collect(Html.joining());
        return Html.concat(
                explain("Rassemblez ce que vous pouvez, au format papier ou PDF : rien n'est obligatoire. Une coche verte apparaît toute seule quand "
                        + "l'information est déjà dans Patria ; sinon, cochez « J'ai ce document » ou « Pas concerné ». Astuce : avec vos relevés bancaires "
                        + "et vos contrats, l'import de l'étape Dépenses fixes (prompt IA) remplit beaucoup de lignes d'un coup."),
                Components.card("Préparer les documents avant un import", null, Html.EMPTY, Html.concat(Html.raw("""
                        <ol class="steps"><li><b>Rassemblez les originaux</b> avec la liste ci-dessous. Une saisie manuelle ou un CSV préparé par vous-même ne nécessite pas d'IA.</li>
                        <li><b>Caviardez une copie</b> avant tout partage : noms, adresses, IBAN, numéros, signatures et QR codes. Les documents restent dans votre navigateur.</li>
                        <li><b>Lancez le contrôle Patria</b> : il recherche les identifiants courants dans le texte PDF encore visible et les mots que vous indiquez.
                        Les images et scans nécessitent une relecture manuelle : le contrôle signale ses limites.</li>
                        <li><b>Relisez la copie téléchargée</b>, puis joignez uniquement celle-ci avec le prompt de l'étape Prêts ou Dépenses fixes. N'envoyez jamais l'original.</li></ol>
                        <p class="muted">« J'ai ce document » indique qu'il est rassemblé, pas qu'il a été caviardé ou contrôlé. Le travail de caviardage n'est pas conservé en quittant cette page.</p>
                        <details><summary>Préparer, caviarder et contrôler les documents ici</summary>"""), RedactionPage.tool(), Html.raw("""
                        </details><p><a href="/aide/caviardage" target="_blank" rel="noopener noreferrer">Ouvrir aussi l'outil dans un autre onglet</a></p>"""))),
                Components.card("Documents", null, Html.of("<span class=\"muted\">%s sur %s rassemblés</span>", ready, documents.size()),
                        Html.concat(Components.progress(documents.isEmpty() ? 0 : (int) (ready * 100 / documents.size())), groups)));
    }

    static boolean isReady(WizardController.Context context, Documents.Document document) {
        return document.auto().test(context.snapshot()) || context.documents().containsKey(document.id());
    }

    static Html documentRow(WizardController.Context context, Documents.Document document) {
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

}
