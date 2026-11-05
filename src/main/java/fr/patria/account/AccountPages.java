package fr.patria.account;

import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pages des foyers, des utilisateurs et de « Mon compte ». HTML uniquement. */
final class AccountPages {
    private AccountPages() {
    }

    // ---- Foyers ----

    /**
     * Un foyer pour la page Foyers : ses membres (noms seulement), ses gestionnaires actifs, les comptes qui y ont acces
     * et l'avancement de leur guide de saisie. Aucun montant.
     */
    record HouseholdCard(Household household, List<HouseholdDirectory.Member> members, long managers, List<String> users, List<AccountProgress> progress,
                         List<User> managerUsers) {
    }

    /** Avancement du guide d'un compte dans un foyer. */
    record AccountProgress(User user, boolean manager, HouseholdDirectory.Progress progress) {
    }

    /**
     * Page Foyers : la reprise des foyers sans gestionnaire, une carte par foyer (membres, comptes, avancement de la
     * configuration, renommer, supprimer), la creation d'un foyer et les foyers supprimes.
     */
    static Html households(List<HouseholdCard> cards, List<Household> orphans, List<User> activeUsers, List<HouseholdDirectory.Archive> archives,
                           Map<String, String> values, Notice notice, String csrf) {
        Html blocks = cards.stream().map(card -> householdCard(card, cards.size(), activeUsers, csrf)).collect(Html.joining());
        Html form = Components.form("/administration/foyers", csrf, false, Components.fields(2,
                Components.input(Input.of("Nom du foyer", "name", Field.TEXT).required().maxLength(60).placeholder("Foyer Martin"), values)), "Créer le foyer");
        Html content = Html.concat(Components.heading("Administration", "Foyers",
                        "Les foyers, leurs membres et l'avancement de leur configuration. Vous créez les comptes et réglez les droits ; "
                                + "vous ne voyez aucune donnée financière : ce sont les gestionnaires de chaque foyer qui saisissent.", Html.EMPTY),
                Components.notice(notice), takeOver(orphans, activeUsers, values, csrf),
                Components.card("Foyers", cards.size(), Html.EMPTY, blocks),
                Components.card("Nouveau foyer", null, Html.EMPTY, form), archives(archives, csrf));
        return Layout.adminSection("Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    /**
     * Foyers sans gestionnaire : personne ne peut y saisir. Deux facons de les confier : a un compte existant, ou a un
     * compte cree ici. Ce compte en devient gestionnaire et reprend la progression du guide de l'administrateur.
     */
    private static Html takeOver(List<Household> orphans, List<User> activeUsers, Map<String, String> values, String csrf) {
        if (orphans.isEmpty()) return Html.EMPTY;
        Html boxes = orphans.stream().map(household -> Html.of("<label class=\"check\"><input type=\"checkbox\" name=\"foyer_%s\" value=\"on\" checked>%s</label>",
                household.id(), household.name())).collect(Html.joining());
        Html intro = Html.raw("""
                <p>Personne ne peut y saisir. L'administrateur ne voit pas les données des foyers : confiez-les à un compte, qui en devient
                <b>gestionnaire</b> (tout voir, tout saisir) et retrouve la progression du Guide de saisie. Les données déjà saisies ne bougent pas.</p>""");
        Html existing = activeUsers.isEmpty() ? Html.EMPTY : Html.of("""
                <form method="post" action="/administration/foyers/reprise" class="stack takeover-form"><p class="eyebrow">À un compte existant</p>%s                <div class="chips-line">%s</div><div class="row-form">%s<button type="submit">Le nommer gestionnaire de ces foyers</button></div></form>""",
                Components.csrfField(csrf), boxes, userSelect("Compte", "compte", activeUsers));
        Html created = Html.of("""
                <form method="post" action="/administration/foyers/reprise" class="stack takeover-form"><p class="eyebrow">%s</p>%s                <div class="chips-line">%s</div>%s
                <div class="form-actions"><button type="submit" class="%s">Créer le compte et lui confier ces foyers</button></div></form>""",
                activeUsers.isEmpty() ? "Créer le compte" : "Ou créer un nouveau compte", Components.csrfField(csrf), boxes,
                Components.fields(3,
                        Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60).placeholder("William"), values),
                        Components.input(Input.of("Identifiant", "login", Field.TEXT).required().maxLength(40).placeholder("william"), values),
                        password("Mot de passe provisoire", "password", "new-password", "À changer à la première connexion.")),
                activeUsers.isEmpty() ? "" : "secondary");
        return Html.of("<section class=\"notice warning takeover\"><b>%s sans gestionnaire</b>%s%s%s</section>",
                orphans.size() > 1 ? orphans.size() + " foyers" : "Un foyer", intro, existing, created);
    }

    /** Liste deroulante des comptes : « Paul (paul) ». */
    private static Html userSelect(String label, String name, List<User> users) {
        return Components.select(label, name, users.stream().map(user -> new Components.Option(String.valueOf(user.id()), user.name() + " (" + user.login() + ")")).toList(),
                users.isEmpty() ? "" : String.valueOf(users.getFirst().id()));
    }

    /** Bases des foyers supprimes, gardees sur le disque tant qu'on ne les efface pas. */
    private static Html archives(List<HouseholdDirectory.Archive> archives, String csrf) {
        if (archives.isEmpty()) return Html.EMPTY;
        Html rows = archives.stream().map(archive -> Html.of("""
                        <tr><td><code>%s</code></td><td>%s</td><td class="num">%s Ko</td><td><form method="post" action="/administration/foyers/archives/effacer"                         data-confirm="Effacer définitivement cette base ? Ses données ne pourront plus être récupérées.">%s<input type="hidden" name="archive" value="%s">                        <button class="danger" type="submit">Effacer définitivement</button></form></td></tr>""",
                archive.name(), archive.deletedOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), Math.max(1, archive.bytes() / 1024),
                Components.csrfField(csrf), archive.name())).collect(Html.joining());
        return Components.card("Foyers supprimés", archives.size(), Html.EMPTY, Html.concat(Html.raw("""
                <p class="muted">Un foyer supprimé n'est pas effacé tout de suite : sa base est mise de côté, au cas où. Ses données restent sur le disque
                du serveur tant que vous ne l'effacez pas ici.</p>"""), Components.table(List.of("Base", "Supprimé le", "#Taille", ""), rows, false, false)));
    }

    private static Html householdCard(HouseholdCard card, int count, List<User> activeUsers, String csrf) {
        Household household = card.household();
        Html members = card.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre pour l'instant.</p>")
                : Html.of("<div class=\"chips-line\">%s</div>", card.members().stream()
                .map(member -> Html.of("<span class=\"badge\">%s%s</span>", member.name(), member.account() ? " · compte joint" : "")).collect(Html.joining()));
        Html steps = card.users().isEmpty() ? Html.raw("""
                <ol class="steps"><li>Ajoutez ses membres ci-dessous (les personnes, puis le compte joint s'il y en a un).</li>
                <li>Créez le compte de chaque personne (Utilisateurs), puis choisissez ci-dessous le gestionnaire, celui qui saisit tout.</li>
                <li>Le gestionnaire remplit le foyer avec le Guide de saisie ; son avancement s'affiche ici.</li></ol>""") : Html.EMPTY;
        Html addMember = Html.of("""
                <form method="post" action="/administration/foyers/%s/membres" class="row-form">%s%s%s<button class="secondary" type="submit">Ajouter le membre</button></form>""",
                household.id(), Components.csrfField(csrf),
                Components.input(Input.of("Nouveau membre", "name", Field.TEXT).required().maxLength(60).placeholder("Prénom, ou « Compte joint »"), Map.of()),
                Components.select("Type", "kind", List.of(new Components.Option("PERSON", "Personne"), new Components.Option("ACCOUNT", "Compte joint")), "PERSON"));
        Html rename = Html.of("""
                <form method="post" action="/administration/foyers/%s/renommer" class="rename-form">%s<input name="name" value="%s" maxlength="60" required\
                 aria-label="Nom du foyer"><button class="secondary" type="submit">Renommer</button></form>""", household.id(), Components.csrfField(csrf), household.name());
        Html delete = household.isPrimary() || count < 2 ? Html.EMPTY : Html.of("""
                <form method="post" action="/administration/foyers/%s/supprimer" data-confirm="Supprimer le foyer « %s » ? Sa base est mise de côté,\
                 ses utilisateurs n'y ont plus accès.">%s<button class="danger" type="submit">Supprimer</button></form>""", household.id(), household.name(), Components.csrfField(csrf));
        // Un seul gestionnaire : une liste avec le gestionnaire actuel (ou « Aucun ») et « Enregistrer ».
        String current = card.managerUsers().isEmpty() ? "" : String.valueOf(card.managerUsers().getFirst().id());
        List<Components.Option> options = new java.util.ArrayList<>();
        options.add(new Components.Option("", "Aucun gestionnaire"));
        activeUsers.forEach(user -> options.add(new Components.Option(String.valueOf(user.id()), user.name() + " (" + user.login() + ")")));
        Html manager = activeUsers.isEmpty() ? Html.raw("<p class=\"muted\">Aucun compte pour l'instant : créez-le dans Utilisateurs, puis choisissez-le ici.</p>")
                : Html.of("""
                <form method="post" action="/administration/foyers/%s/gestionnaire" class="row-form">%s%s<button class="secondary" type="submit">Enregistrer</button></form>
                <p class="muted small">%s</p>""", household.id(), Components.csrfField(csrf),
                Components.select("Gestionnaire", "compte", options, current),
                current.isEmpty() ? "Personne ne peut saisir les biens, les prêts ni la répartition : choisissez le compte qui saisit tout."
                        : "Il voit et saisit tout le foyer. En choisir un autre le remplace ; l'ancien garde ses autres vues.");
        return Html.of("""
                        <section class="household-card" id="foyer-%s"><h3>%s</h3>
                        <p class="eyebrow">Membres</p>%s%s%s
                        %s
                        <p class="eyebrow">Comptes qui y ont accès</p><p class="muted">%s</p>
                        <p class="eyebrow">Avancement de la configuration</p>%s
                        <div class="household-actions">%s%s</div></section>""",
                household.id(), household.name(), members, steps, addMember, manager,
                card.users().isEmpty() ? "Aucun." : String.join(", ", card.users()), progress(card.progress()), rename, delete);
    }

    /** Guide de saisie de chaque compte : pourcentage, etapes faites, passees, restantes, derniere revue annuelle. */
    private static Html progress(List<AccountProgress> progress) {
        if (progress.isEmpty()) return Html.raw("<p class=\"muted\">Pas encore de compte pour saisir.</p>");
        Html rows = progress.stream().map(entry -> {
            HouseholdDirectory.Progress step = entry.progress();
            int remaining = step.total() - step.done() - step.skipped();
            return Html.of("""
                    <tr><td>%s%s</td><td><span class="progress-inline"><i style="width:%s%"></i></span> %s %</td><td class="num">%s</td><td class="num">%s</td>\
                    <td class="num">%s</td><td>%s</td><td>%s</td></tr>""",
                    entry.user().name(), entry.manager() ? Html.raw(" <span class=\"badge\">gestionnaire</span>") : Html.EMPTY, step.percent(), step.percent(),
                    step.done(), step.skipped(), remaining, step.next() == null ? "Terminé" : step.next(),
                    step.reviewedOn() == null ? "Jamais" : step.reviewedOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }).collect(Html.joining());
        return Components.table(List.of("Compte", "Guide de saisie", "#Faites", "#Passées", "#Restantes", "Prochaine étape", "Revue annuelle"), rows, false, false);
    }

    // ---- Utilisateurs ----

    /** Choix « ce compte represente » a la creation : un membre existant ou nouveau d'un foyer, ou aucun. */
    record MemberChoice(String value, String label, String group) {
    }

    static Html users(List<User> users, Map<Long, List<Access>> accesses, List<MemberChoice> choices, Map<String, String> values, Notice notice, String csrf) {
        Html rows = users.stream().map(user -> Html.of("<tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>",
                user.name(), user.login(), user.active() ? Html.raw("<span class=\"badge paid\">Actif</span>") : Html.raw("<span class=\"badge\">Désactivé</span>"),
                accesses.getOrDefault(user.id(), List.of()).stream().map(Access::householdName).reduce((a, b) -> a + ", " + b).orElse("Aucun foyer"),
                Components.link("/administration/utilisateur/" + user.id(), "Modifier"))).collect(Html.joining());
        Html form = Components.form("/administration/utilisateurs", csrf, false, Components.fields(3,
                Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60).placeholder("Paul"), values),
                Components.input(Input.of("Identifiant", "login", Field.TEXT).required().maxLength(40).placeholder("paul")
                        .help("Lettres, chiffres, point, tiret. Sert à la connexion."), values),
                password("Mot de passe provisoire", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères ; l'utilisateur le changera à sa première connexion."),
                memberSelect(choices, values.getOrDefault("membre", ""))),
                "Créer l'utilisateur");
        Html content = Html.concat(Components.heading("Administration", "Utilisateurs",
                        "Vous seul créez les comptes et réglez ce que chacun voit. Chaque accès ouvert est visible par les comptes du foyer (icône « qui voit quoi ») "
                                + "et inscrit au journal (Sécurité).",
                        Html.EMPTY),
                Components.notice(notice),
                Components.card("Nouvel utilisateur", null, Html.EMPTY, form),
                Components.card("Utilisateurs", users.size(), Html.EMPTY, users.isEmpty() ? Html.raw("<p class=\"empty\">Aucun utilisateur.</p>")
                        : Components.table(List.of("Nom", "Identifiant", "État", "Foyers", ""), rows, false, false)));
        return Layout.adminSection("Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    /** Liste deroulante groupee par foyer : aucun, un membre existant, ou un nouveau membre au nom de l'utilisateur. */
    private static Html memberSelect(List<MemberChoice> choices, String selected) {
        StringBuilder options = new StringBuilder();
        options.append(Html.of("<option value=\"\"%s>Aucun pour l'instant (droits à régler ensuite)</option>", selected.isEmpty() ? Html.raw(" selected") : Html.EMPTY));
        String group = null;
        for (MemberChoice choice : choices) {
            if (!choice.group().equals(group)) {
                if (group != null) options.append("</optgroup>");
                options.append(Html.of("<optgroup label=\"%s\">", choice.group()));
                group = choice.group();
            }
            options.append(Html.of("<option value=\"%s\"%s>%s</option>", choice.value(), choice.value().equals(selected) ? Html.raw(" selected") : Html.EMPTY, choice.label()));
        }
        if (group != null) options.append("</optgroup>");
        return Html.of("<label>Ce compte représente<select name=\"membre\">%s</select><span class=\"help\">Le membre du foyer que cette personne est : "
                + "elle verra et modifiera ses lignes. Les autres vues se règlent ensuite dans ses droits.</span></label>", Html.raw(options.toString()));
    }

    /** Membres d'un foyer, avec ce que l'utilisateur y voit (vide : pas d'acces). */
    record HouseholdAccess(Household household, List<HouseholdDirectory.Member> members, Optional<Access> access) {
    }

    static Html user(User user, List<HouseholdAccess> households, boolean provisional, Notice notice, String csrf) {
        Html identity = Components.form("/administration/utilisateur/" + user.id(), csrf, false, Html.concat(Components.fields(2,
                        Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60), Map.of("name", user.name())),
                        Html.of("<label>Identifiant<input value=\"%s\" disabled></label>", user.login())),
                checkbox("active", "Compte actif (décoché : connexion impossible)", user.active())), "Enregistrer");
        Html accesses = households.stream().map(entry -> accessForm(user, entry, "/administration/utilisateur/" + user.id() + "/foyer/" + entry.household().id(), csrf))
                .collect(Html.joining());
        Html reset = Html.concat(Html.raw(provisional
                        ? "<p class=\"muted\">Mot de passe <b>provisoire</b> : l'utilisateur devra le changer à sa prochaine connexion.</p>"
                        : "<p class=\"muted\">Mot de passe choisi par l'utilisateur. Le réinitialiser le déconnecte ; il devra en choisir un nouveau à la "
                        + "connexion suivante, et Patria lui dira que l'administrateur l'a réinitialisé.</p>"),
                Components.form("/administration/utilisateur/" + user.id() + "/mot-de-passe", csrf, false,
                        Components.fields(2, password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères.")),
                        "Réinitialiser le mot de passe"));
        Html content = Html.concat(Components.backLink("/administration/utilisateurs", "Utilisateurs"),
                Components.heading("Utilisateur", user.name(), user.login(), Html.EMPTY), Components.notice(notice),
                Components.card("Compte", null, Html.EMPTY, identity),
                Html.of("""
                        <section class="card section"><div class="section-title"><h2>Foyers et droits</h2></div>
                        <p class="muted">Un foyer par bloc. Chaque case dit ce qu'elle ouvre ; le résumé en haut du bloc dit ce que l'utilisateur
                        peut faire aujourd'hui. Pensez à « Enregistrer les droits » pour chaque foyer.</p>%s</section>""", accesses),
                Components.card("Mot de passe", null, Html.EMPTY, reset),
                Components.dangerZone("Supprimer cet utilisateur", "Supprime le compte et ses droits. Les données des foyers ne changent pas.",
                        "/administration/utilisateur/" + user.id() + "/supprimer", "Supprimer l'utilisateur « " + user.name() + " » ?", "Supprimer l'utilisateur", csrf));
        return Layout.adminSection(user.name() + " · Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    private static Html accessForm(User user, HouseholdAccess entry, String action, String csrf) {
        Access access = entry.access().orElse(null);
        Html members = entry.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre dans ce foyer pour l'instant : ajoutez-les dans Membres.</p>")
                : entry.members().stream().map(member -> option("member_" + member.id(),
                member.account() ? "Gère « " + member.name() + " »" : "C'est " + member.name(),
                (member.account() ? "Ce compte gère le compte joint « " + member.name() + " » : " : "Ce compte représente " + member.name() + " : ")
                        + "onglet « " + member.name() + " » dans Prévisionnel et Budget moyen, saisie et modification de "
                        + (member.account() ? "ses dépenses et virements." : "ses revenus, dépenses et virements."),
                access != null && access.members().contains(member.id()))).collect(Html.joining());
        return Html.of("""
                        <form method="post" action="%s" class="access-form stack" id="utilisateur-%s" data-access-form>%s
                        <div class="access-head"><h3>%s</h3><p class="access-summary %s">%s</p></div>
                        %s
                        <fieldset class="access-details" data-access-details><legend class="eyebrow">Ce qu'il voit</legend>%s%s%s
                        <p class="eyebrow">Membres : qui ce compte représente</p><div class="access-options">%s</div>
                        <p class="muted small">Une ligne qui concerne seulement ses membres cochés est modifiable. Une ligne partagée avec un membre non coché
                        (ex. « parts égales » avec une autre personne) reste visible en lecture seule. Il ne gère jamais les membres, les biens, la répartition,
                        les foyers ni les utilisateurs.</p></fieldset>
                        <div class="form-actions"><button type="submit">Enregistrer les droits</button></div></form>""",
                action, user.id(), Components.csrfField(csrf), entry.household().name(),
                access == null ? "off" : "on", summary(access, entry.members()),
                option("access", "Accès à ce foyer", "Le foyer apparaît pour lui (sélecteur de l'en-tête s'il en a plusieurs). Décoché : il ne le voit pas, les autres cases sont ignorées.",
                        access != null),
                option("manager", "Gestionnaire du foyer : tout voir, tout saisir", "Dans ce foyer : membres, revenus, dépenses, biens, prêts, répartition, "
                        + "Guide de saisie et imports. Un seul par foyer : le cocher remplace le gestionnaire actuel. Les cases ci-dessous sont alors sans objet.",
                        access != null && access.manager()),
                option("whole", "Vue « Foyer » : les totaux", "Onglet « Foyer » dans Prévisionnel et Budget moyen, avec les revenus et dépenses de tous les membres, et toutes les lignes "
                        + "en lecture seule dans Administration. Ne donne aucun droit de modification.", access != null && access.wholeHousehold()),
                option("estate", "Immobilier, en lecture seule", "Menu Immobilier : biens, prêts, mensualités, prix du marché. Sans cette case, les mensualités et "
                        + "charges des biens restent comptées dans son budget, mais il n'ouvre pas les fiches.", access != null && access.realEstate()),
                members);
    }

    /** Ce que l'utilisateur peut faire dans ce foyer, en une phrase. */
    private static String summary(Access access, List<HouseholdDirectory.Member> members) {
        if (access == null) return "Pas d'accès à ce foyer.";
        if (access.manager()) return "Gestionnaire : voit et modifie tout le foyer.";
        List<String> names = members.stream().filter(member -> access.members().contains(member.id())).map(HouseholdDirectory.Member::name).toList();
        StringBuilder text = new StringBuilder("Accès ouvert. ");
        if (access.wholeHousehold()) text.append("Voit les totaux du foyer. ");
        if (names.isEmpty()) text.append("Ne modifie rien (aucun membre coché). ");
        else text.append("Voit et modifie les lignes de ").append(String.join(", ", names)).append(". ");
        text.append(access.realEstate() ? "Consulte l'immobilier." : "Pas d'immobilier.");
        return text.toString();
    }

    private static Html option(String name, String label, String help, boolean checked) {
        return Html.of("<label class=\"check option\"><input type=\"checkbox\" name=\"%s\" value=\"on\"%s><span><b>%s</b><span class=\"help\">%s</span></span></label>",
                name, checked ? Html.raw(" checked") : Html.EMPTY, label, help);
    }

    // ---- Mon compte ----

    /**
     * Mot de passe choisi par l'administrateur (creation ou reinitialisation) : l'utilisateur en choisit un autre avant
     * toute autre page ; l'administrateur ne le connait donc plus.
     */
    static Html newPassword(User user, java.time.LocalDate setOn, boolean forced, Notice notice, String csrf) {
        String when = setOn == null ? "" : " le " + setOn.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        Html intro = forced ? Html.of("""
                <p class="notice warning">Votre mot de passe a été choisi par l'administrateur%s (création du compte ou réinitialisation). Choisissez le vôtre :
                l'administrateur ne le connaîtra pas, et ne pourra pas se connecter à votre place.</p>""", when)
                : Html.raw("<p class=\"muted\">Votre mot de passe est déjà le vôtre ; vous pouvez le changer dans Mon compte.</p>");
        Html form = Components.form("/compte/mot-de-passe", csrf, false, Components.fields(3,
                password("Mot de passe actuel (provisoire)", "current", "current-password", null),
                password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères."),
                password("Confirmation", "confirm", "new-password", null)), "Choisir ce mot de passe");
        Html content = Html.concat(Components.heading("Mon compte", "Choisissez votre mot de passe", user.name() + " · " + user.login(), Html.EMPTY),
                Components.notice(notice), intro, forced ? Components.card("Nouveau mot de passe", null, Html.EMPTY, form) : Html.EMPTY);
        return Layout.page("Mot de passe · Patria", Layout.Section.ACCOUNT, csrf, content);
    }

    static Html account(String name, String login, boolean admin, Notice notice, String csrf) {
        Html body = admin ? Html.raw("""
                <p class="muted">Vous êtes connecté en administrateur. Ce mot de passe est défini dans l'environnement du serveur (APP_PASSWORD) :
                il se change dans la configuration (compose.yaml), puis en redémarrant Patria.</p>""")
                : Components.form("/compte/mot-de-passe", csrf, false, Components.fields(3,
                        password("Mot de passe actuel", "current", "current-password", null),
                        password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères."),
                        password("Confirmation", "confirm", "new-password", null)), "Changer le mot de passe");
        Html content = Html.concat(Components.heading("Mon compte", name, login, Html.EMPTY), Components.notice(notice),
                Components.card("Mot de passe", null, Html.EMPTY, body),
                Components.card("Accueil", null, Html.EMPTY, Html.raw("""
                        <p class="muted">Les quatre écrans de la première connexion : ce que fait Patria, pour qui, la confidentialité.</p>
                        <a class="button secondary" href="/bienvenue">Revoir l'accueil</a>""")));
        return Layout.page("Mon compte · Patria", Layout.Section.ACCOUNT, csrf, content);
    }

    private static Html password(String label, String name, String autocomplete, String help) {
        return Html.of("<label>%s <span class=\"required-mark\">*</span><input name=\"%s\" type=\"password\" autocomplete=\"%s\" minlength=\"%s\" maxlength=\"72\" required>%s</label>",
                label, name, autocomplete, name.equals("current") ? 1 : AccountRepository.MIN_PASSWORD,
                help == null ? Html.EMPTY : Html.of("<span class=\"help\">%s</span>", help));
    }

    private static Html checkbox(String name, String label, boolean checked) {
        return Html.of("<label class=\"check\"><input type=\"checkbox\" name=\"%s\" value=\"on\"%s>%s</label>", name, checked ? Html.raw(" checked") : Html.EMPTY, label);
    }
}
