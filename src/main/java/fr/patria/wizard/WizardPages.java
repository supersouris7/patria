// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
                <li><b>Pour aller vite</b>, la première étape importe le fichier des opérations de votre banque (le plus simple, sans IA), ou la réponse d'une IA
                (Le Chat, ChatGPT, Claude, Gemini) à qui vous donnez vos documents. Patria ne lui envoie rien : <a href="/confidentialite">comment protéger vos données</a>.</li></ul>""");
        Html content = Html.concat(Components.heading("Patria", "Guide de saisie", context.admin() ? "Pour tout le foyer." : "Pour vos propres revenus et dépenses.",
                        actions),
                Components.disclosure("Comment ça marche", intro, !started),
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
            case DOCUMENTS -> documents(context, values.getOrDefault("q", ""));
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
                    + "tout seul et apparaît dans le budget. Pour la bourse et les cryptos, la page Bourse importe le relevé de votre courtier (Trade Republic, Revolut, "
                    + "Degiro, Coinbase…) et crée le compte : la valeur suit ensuite les cours du jour."), context.savings().get());
            case HEALTH -> healthScope(context);
            case SUMMARY -> summary(context);
        };
        Html heading = Components.heading("Guide de saisie" + (context.review() ? " · revue" : ""),
                step.title(), step.summary(), Html.EMPTY);
        Html bar = Html.of("<div class=\"wizard-bar\">%s<details class=\"wizard-plan\"><summary>Plan du guide</summary><ol class=\"wizard-plan-steps\">%s</ol></details></div>",
                progressBar(context, step), stepper(context, step));
        Html content = Html.concat(bar, heading, Components.notice(notice), toSort(context, step), body, step == Step.SUMMARY ? Html.EMPTY : footer(context, step));
        Html layout = Html.of("<div class=\"wizard wizard-linear\"><div class=\"wizard-body\">%s</div></div>", content);
        return Layout.page(step.title() + " · Guide de saisie · Patria", Layout.Section.ASSISTANT, context.csrf(), layout);
    }

    /** Barre de progression en segments : une case par etape (faite, passee, en cours, a venir), et l'etape en cours nommee. */
    static Html progressBar(WizardController.Context context, Step current) {
        Html segments = context.steps().stream().map(step -> Html.of("<li class=\"%s%s\" title=\"%s\"></li>", css(context, step), step == current ? " current" : "",
                step.title())).collect(Html.joining());
        long finished = context.steps().stream().filter(context.progress()::containsKey).count();
        return Html.of("<div class=\"wizard-progress\"><ol aria-hidden=\"true\">%s</ol><p><b>Étape %s sur %s</b> · %s <span class=\"muted\">· %s faite%s</span></p></div>",
                segments, context.steps().indexOf(current) + 1, context.steps().size(), current.title(), finished, finished > 1 ? "s" : "");
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
    static Html documents(WizardController.Context context, String query) {
        List<Documents.Document> documents = Documents.forViewer(context.admin());
        long ready = documents.stream().filter(document -> isReady(context, document)).count();
        Map<ImportInbox.Type, Long> pile = context.inbox().stream().collect(java.util.stream.Collectors.groupingBy(ImportInbox.Item::type,
                () -> new java.util.EnumMap<>(ImportInbox.Type.class), java.util.stream.Collectors.counting()));
        Html pileCard = Components.card("Pile « À trier »", context.inbox().size(), Html.EMPTY, pile.isEmpty()
                ? Html.raw("<p class=\"muted\">Vide pour l'instant. Après l'envoi de la réponse de l'IA, ses lignes attendent ici que vous les triiez.</p>")
                : Html.of("<ul class=\"pile\">%s</ul><p class=\"muted\">Rien n'est encore dans votre budget : chaque ligne se valide ou se refuse à l'étape indiquée, avec « Suivant ».</p>",
                pile.entrySet().stream().map(entry -> Html.of("<li><b>%s</b> %s <span class=\"muted\">→ étape « %s »</span></li>", entry.getValue(),
                        entry.getKey().label().toLowerCase(java.util.Locale.FRENCH), entry.getKey().step().title())).collect(Html.joining())));
        Html methods = importMethods(context);
        Html checklist = Html.of("""
                <form method="post" action="/assistant/documents/liste" class="doc-checklist">%s%s%s
                <div class="form-actions"><button class="secondary" type="submit">Enregistrer la liste</button></div></form>""",
                Components.csrfField(context.csrf()), modeField(context), documents.stream().map(Documents.Document::group).distinct().map(group -> Html.of(
                        "<fieldset><legend>%s</legend>%s</fieldset>", group, documents.stream().filter(document -> document.group().equals(group)).map(document -> {
                            boolean auto = document.auto().test(context.snapshot());
                            boolean have = auto || context.documents().get(document.id()) == WizardRepository.DocumentStatus.HAVE;
                            return Html.of("<label class=\"check\" title=\"%s\"><input type=\"checkbox\" name=\"doc_%s\" value=\"on\"%s%s> %s%s</label>", document.where(),
                                    document.id(), Html.raw(have ? " checked" : ""), Html.raw(auto ? " disabled" : ""), document.label(),
                                    auto ? Html.raw(" <span class=\"badge paid\">déjà dans Patria</span>") : Html.EMPTY);
                        }).collect(Html.joining()))).collect(Html.joining()));
        return Html.concat(
                Html.of("<div id=\"import\">%s</div>", Components.card("Comment voulez-vous remplir Patria ?", null, Html.EMPTY, methods)),
                // La liste et la recherche « ou trouver » s'ouvrent d'elles-memes quand on cherche une banque.
                Components.disclosure("Quels documents rassembler, et où les trouver ? (" + ready + " sur " + documents.size() + ")",
                        Html.concat(finder(context, documents, query), checklist), !query.isBlank()),
                pileCard);
    }

    /**
     * Trois facons d'importer, une seule ouverte a la fois : le fichier de la banque (le plus simple, sans IA), une IA
     * (pas a pas, avec la consigne a copier), ou a la main. Tout arrive dans la pile « A trier » ; rien n'entre dans le
     * budget sans validation.
     */
    private static Html importMethods(WizardController.Context context) {
        Html csrf = Html.concat(Components.csrfField(context.csrf()), modeField(context));
        Html bank = Html.of("""
                <ol class="howto-steps">
                <li><b>Connectez-vous au site de votre banque</b>, de préférence sur ordinateur : les applications mobiles proposent rarement l'export.</li>
                <li><b>Ouvrez la liste des opérations</b> du compte courant et cherchez « Exporter », « Télécharger » ou une flèche vers le bas.
                Pas trouvé ? Tapez le nom de votre banque dans <a href="#ou-trouver">Où trouver vos documents ?</a></li>
                <li><b>Choisissez le format CSV</b> (ou OFX, QIF) sur au moins 3 mois, idéalement 12, puis enregistrez le fichier.</li>
                <li><b>Déposez-le ici :</b></li></ol>
                <form method="post" action="/assistant/documents/importer" class="stack" enctype="multipart/form-data">%s
                <label>Fichier de la banque (CSV, OFX ou QIF)<input name="csv" type="file" accept=".csv,.ofx,.qif,.txt" required></label>
                <div class="form-actions"><button type="submit">Analyser le fichier</button></div></form>
                <p class="muted small">Patria repère les prélèvements et revenus qui reviennent chaque mois avec le même montant (loyer, abonnements, salaire)
                et les met dans la pile « À trier ». Les dépenses variables (courses, carburant) se saisissent ensuite. Le fichier n'est pas conservé.</p>""", csrf);
        Html ai = Html.of("""
                %s<ol class="howto-steps">
                <li><b>Ouvrez une IA</b> dans un autre onglet : <a href="https://chat.mistral.ai" target="_blank" rel="noopener noreferrer">Le Chat (Mistral)</a>,
                <a href="https://chatgpt.com" target="_blank" rel="noopener noreferrer">ChatGPT</a>, <a href="https://claude.ai" target="_blank" rel="noopener noreferrer">Claude</a>
                ou <a href="https://gemini.google.com" target="_blank" rel="noopener noreferrer">Gemini</a>. Un compte gratuit suffit.</li>
                <li><b>Copiez la consigne</b> et collez-la dans la conversation de l'IA.<div class="llm-prompt">%s</div></li>
                <li><b>Joignez vos documents</b> avec le trombone de l'IA : bulletins de salaire, relevés, avis d'impôt, contrats (PDF, photos ou captures).
                Masquez d'abord IBAN et numéros de compte si vous le souhaitez.</li>
                <li><b>Copiez toute la réponse de l'IA</b> (bouton « Copier » sous sa réponse) et <b>collez-la ici :</b></li></ol>
                <form method="post" action="/assistant/documents/importer" class="stack" enctype="multipart/form-data">%s
                <label>Réponse de l'IA (vous pouvez coller en plusieurs fois)<textarea name="texte" rows="10" required
                placeholder="REVENU;Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;"></textarea></label>
                <div class="form-actions"><button type="submit">Ajouter à la pile « À trier »</button></div></form>%s""",
                Components.aiWarning(), Components.promptCopy("prompt-universel", ImportInbox.prompt(context.visibility().people()), "Copier la consigne"), csrf,
                localAi(context, csrf));
        Html manual = Html.of("""
                <p>Le guide vous fait saisir, un écran à la fois, vos revenus, votre logement, vos dépenses fixes et vos impôts. Gardez vos derniers
                bulletins de salaire et relevés sous la main.</p>
                <form method="post" action="%s/terminer">%s<button type="submit">Saisir à la main, étape par étape →</button></form>""", Step.DOCUMENTS.path(), csrf);
        return Html.of("""
                <p class="muted">Choisissez une méthode. Rien n'entre dans votre budget avant que vous l'ayez validé, ligne par ligne.</p>
                <div class="import-methods">%s%s%s</div>""",
                method("Avec le fichier de ma banque", "Le plus simple · sans IA · charges et revenus réguliers", bank, true),
                method("Avec une IA", "Le plus complet · salaires, impôts, contrats · 10 minutes", ai, false),
                method("À la main", "Sans fichier · écran par écran", manual, false));
    }

    private static Html method(String title, String hint, Html body, boolean open) {
        return Html.of("<details class=\"import-method\" name=\"methode\"%s><summary><b>%s</b><small>%s</small></summary><div class=\"import-method-body\">%s</div></details>",
                Html.raw(open ? " open" : ""), title, hint, body);
    }

    /** IA locale de l'installation, si l'administrateur l'a activee : le document est converti sans rien envoyer dehors. */
    private static Html localAi(WizardController.Context context, Html csrf) {
        Html input = Components.localAiInput("tout|" + context.visibility().people().stream().map(person -> person.name() + (person.isAccount() ? "*" : ""))
                .collect(java.util.stream.Collectors.joining(",")));
        if (input.isEmpty()) return Html.EMPTY;
        return Html.of("""
                <form method="post" action="/assistant/documents/importer" class="stack" enctype="multipart/form-data">%s%s
                <div class="form-actions"><button class="secondary" type="submit">Convertir avec l'IA locale</button></div></form>""", csrf, input);
    }

    /**
     * Recherche « ou trouver » : on tape sa banque ou un organisme, seules ses etapes s'affichent, document par
     * document. Lien vers le tutoriel complet.
     */
    private static Html finder(WizardController.Context context, List<Documents.Document> documents, String query) {
        String wanted = DocumentGuides.normalize(query);
        Html results = Html.EMPTY;
        if (!wanted.isEmpty()) {
            Html found = documents.stream().flatMap(document -> DocumentGuides.forDocument(document.id()).stream()
                            .filter(guide -> DocumentGuides.normalize(guide.name()).contains(wanted))
                            .map(guide -> Html.of("<div class=\"howto-result\"><b>%s</b> <span class=\"muted\">· %s</span><ol>%s</ol></div>", document.label(), guide.name(),
                                    guide.steps().stream().map(step -> Html.of("<li>%s</li>", step)).collect(Html.joining()))))
                    .collect(Html.joining());
            results = found.isEmpty() ? Html.of("<p class=\"muted\">Rien pour « %s ». %s</p>", query, DocumentGuides.FALLBACK) : found;
        }
        Html names = DocumentGuides.names().stream().map(name -> Html.of("<option value=\"%s\">", name)).collect(Html.joining());
        return Html.of("""
                <form method="get" action="/assistant/documents" class="howto-search" id="ou-trouver" role="search">%s<label>Où trouver vos documents ?                <input type="search" name="q" value="%s" list="plateformes" placeholder="Votre banque ou organisme : LCL, CAF, Trade Republic…"></label>                <datalist id="plateformes">%s</datalist><button class="secondary" type="submit">Chercher</button>                <a class="button secondary" href="/assistant/documents/aide">Tutoriel banque par banque</a></form>%s""",
                context.review() ? Html.of("<input type=\"hidden\" name=\"mode\" value=\"%s\">", WizardController.REVIEW) : Html.EMPTY, query, names, results);
    }

    /** Les plateformes d'un document : une ligne depliable par banque ou organisme, ses etapes numerotees. */
    private static Html guideList(List<DocumentGuides.Guide> guides) {
        return guides.stream().map(guide -> Html.of("<details><summary>%s</summary><ol>%s</ol></details>", guide.name(),
                guide.steps().stream().map(step -> Html.of("<li>%s</li>", step)).collect(Html.joining()))).collect(Html.joining());
    }

    /** Tutoriel complet : pour chaque document qui en a, ou le recuperer sur chaque plateforme. */
    static Html guides(WizardController.Context context) {
        Html sections = Documents.forViewer(context.admin()).stream().filter(document -> !DocumentGuides.forDocument(document.id()).isEmpty())
                .map(document -> Components.card(document.label(), null, Html.EMPTY, Html.of("<p class=\"muted\">%s</p><div class=\"howto-list\">%s</div>",
                        document.where(), guideList(DocumentGuides.forDocument(document.id()))))).collect(Html.joining());
        Html content = Html.concat(Components.heading("Guide de saisie", "Où trouver vos documents",
                        "Étape par étape, banque par banque. Chemins vérifiés en " + DocumentGuides.CHECKED + " : les menus peuvent avoir changé.",
                        Html.raw("<a class=\"button secondary\" href=\"/assistant/documents\">Retour aux documents</a>")),
                Html.of("<p class=\"muted\">%s</p>", DocumentGuides.FALLBACK), sections);
        return Layout.page("Où trouver vos documents · Patria", Layout.Section.ASSISTANT, context.csrf(), content);
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
