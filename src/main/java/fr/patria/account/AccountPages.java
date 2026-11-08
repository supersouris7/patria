package fr.patria.account;

import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Format;
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
     * Un foyer pour la page Foyers : ses membres (noms seulement), les droits de chaque compte et l'avancement de leur
     * guide de saisie. Aucun montant.
     */
    record HouseholdCard(Household household, List<HouseholdDirectory.Member> members, Map<User, Access> accesses, List<AccountProgress> progress) {
    }

    /** Avancement du guide d'un compte dans un foyer. */
    record AccountProgress(User user, HouseholdDirectory.Progress progress) {
    }

    /**
     * Page Foyers : la reprise des foyers sans acces, une carte par foyer (membres, tableau des droits, avancement de
     * la configuration, renommer, supprimer), la creation d'un foyer et les foyers supprimes.
     */
    static Html households(List<HouseholdCard> cards, List<Household> orphans, List<User> activeUsers, boolean recommendTwoFactor,
                           List<HouseholdDirectory.Archive> archives, Map<String, String> values, Notice notice, String csrf) {
        Html blocks = cards.stream().map(card -> householdCard(card, cards.size(), activeUsers, csrf, notice != null && notice.error())).collect(Html.joining());
        Html form = Components.form("/administration/foyers", csrf, false, Components.fields(2,
                Components.input(Input.of("Nom du foyer", "name", Field.TEXT).required().maxLength(60).placeholder("Foyer Martin"), values)), "Créer le foyer");
        Html content = Html.concat(Components.heading("Administration", "Foyers",
                         "Foyers, membres et droits : qui voit quoi. Aucune donnée financière n'est visible ici.", Html.EMPTY),
                Components.notice(notice), recommendTwoFactor ? Html.raw("""
                        <p class="notice error"><b>Double authentification fortement recommandée</b> : Patria est accessible depuis Internet.
                        <a href="/compte/double-authentification">L'activer pour le compte administrateur</a></p>""") : Html.EMPTY,
                takeOver(orphans, activeUsers, values, csrf),
                Components.card("Foyers", cards.size(), Html.EMPTY, blocks),
                Components.disclosure("Créer un foyer", form, notice != null && notice.error()),
                Components.disclosure("Membre, compte et droits : quelle différence ?", Html.raw("""
                        <p>Un <b>membre du foyer</b> figure dans le budget (personne ou compte joint) ; seul l'administrateur en ajoute ou en supprime.
                        Un <b>compte</b> permet d'ouvrir Patria ; seul l'administrateur en crée (Utilisateurs). Le <b>tableau des droits</b> de chaque foyer
                        dit exactement ce que chaque compte voit : une case cochée donne l'accès complet (voir et modifier). L'administrateur ne consulte
                        pas les finances des foyers.</p>"""), false), archives(archives, csrf));
        return Layout.adminSection("Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    /**
     * Foyers auxquels aucun compte n'a acces. Deux facons de les confier : a un compte existant, ou a un compte cree
     * ici. Ce compte recoit tous les droits et reprend la progression du guide de l'administrateur.
     */
    private static Html takeOver(List<Household> orphans, List<User> activeUsers, Map<String, String> values, String csrf) {
        if (orphans.isEmpty()) return Html.EMPTY;
        Html boxes = orphans.stream().map(household -> Html.of("<label class=\"check\"><input type=\"checkbox\" name=\"foyer_%s\" value=\"on\" checked>%s</label>",
                household.id(), household.name())).collect(Html.joining());
        Html intro = Html.raw("""
                <p>Personne ne peut y saisir. L'administrateur ne voit pas les données des foyers : confiez-les à un compte, qui reçoit
                <b>tous les droits</b> (cases ajustables ensuite dans le tableau des droits) et retrouve la progression du Guide de saisie.
                Les données déjà saisies ne bougent pas.</p>""");
        Html existing = activeUsers.isEmpty() ? Html.EMPTY : Html.of("""
                <form method="post" action="/administration/foyers/reprise" class="stack takeover-form"><p class="eyebrow">À un compte existant</p>%s\
                <div class="chips-line">%s</div><div class="row-form">%s<button type="submit">Lui confier ces foyers</button></div></form>""",
                Components.csrfField(csrf), boxes, userSelect("Compte", "compte", activeUsers));
        Html created = Html.of("""
                <form method="post" action="/administration/foyers/reprise" class="stack takeover-form"><p class="eyebrow">%s</p>%s\
                <div class="chips-line">%s</div>%s
                <div class="form-actions"><button type="submit" class="%s">Créer le compte et lui confier ces foyers</button></div></form>""",
                activeUsers.isEmpty() ? "Créer le compte" : "Ou créer un nouveau compte", Components.csrfField(csrf), boxes,
                Components.fields(3,
                        Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60).placeholder("William"), values),
                        Components.input(Input.of("Identifiant", "login", Field.TEXT).required().maxLength(40).placeholder("william"), values),
                        password("Mot de passe provisoire", "password", "new-password", "À changer à la première connexion.")),
                activeUsers.isEmpty() ? "" : "secondary");
        return Html.of("<section class=\"notice warning takeover\"><b>%s sans aucun accès</b>%s%s%s</section>",
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
                        <tr><td><code>%s</code></td><td>%s</td><td class="num">%s Ko</td><td><form method="post" action="/administration/foyers/archives/effacer" \
                        data-confirm="Effacer définitivement cette base ? Ses données ne pourront plus être récupérées.">%s<input type="hidden" name="archive" value="%s">\
                        <button class="danger" type="submit">Effacer définitivement</button></form></td></tr>""",
                archive.name(), archive.deletedOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), Math.max(1, archive.bytes() / 1024),
                Components.csrfField(csrf), archive.name())).collect(Html.joining());
        return Components.card("Foyers supprimés", archives.size(), Html.EMPTY, Html.concat(Html.raw("""
                <p class="muted">Un foyer supprimé n'est pas effacé tout de suite : sa base est mise de côté, au cas où. Ses données restent sur le disque
                du serveur tant que vous ne l'effacez pas ici.</p>"""), Components.table(List.of("Base", "Supprimé le", "#Taille", ""), rows, false, false)));
    }

    private static Html householdCard(HouseholdCard card, int count, List<User> activeUsers, String csrf, boolean open) {
        Household household = card.household();
        Html members = card.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre pour l'instant.</p>")
                : Html.of("<div class=\"chips-line\">%s</div>", card.members().stream().map(member -> Html.of("""
                        <form method="post" action="/administration/foyers/%s/membre/%s/supprimer" class="badge member-chip" data-confirm="Supprimer « %s » \
                        et ses lignes propres ? Les lignes partagées gardent les autres membres.">%s%s%s<button type="submit" class="link-button" \
                        aria-label="Supprimer %s" title="Supprimer">×</button></form>""", household.id(), member.id(), member.name(), Components.csrfField(csrf),
                        member.name(), member.account() && !member.name().toLowerCase(java.util.Locale.ROOT).contains("joint") ? " · compte joint" : "", member.name())).collect(Html.joining()));
        Html steps = card.accesses().isEmpty() ? Html.raw("""
                <ol class="steps"><li>Ajoutez ses membres ci-dessous (les personnes, puis le compte joint s'il y en a un).</li>
                <li>Créez le compte de chaque personne (Utilisateurs).</li>
                <li>Cochez ci-dessous ce que chaque compte voit ; il remplit ensuite le foyer avec le Guide de saisie.</li></ol>""") : Html.EMPTY;
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
        long withAccess = card.accesses().entrySet().stream().filter(entry -> entry.getKey().active()).count();
        return Html.of("""
                         <section class="household-card" id="foyer-%s"><details%s><summary><b>%s</b><span class="muted">%s · %s · %s</span></summary><div class="disclosure-body">
                        <p class="eyebrow">Membres</p>%s%s%s
                        <p class="eyebrow">Qui voit quoi</p>%s
                        <p class="eyebrow">Avancement de la configuration</p>%s
                         <div class="household-actions">%s%s</div></div></details></section>""",
                household.id(), Html.raw(open ? " open" : ""), household.name(), Format.plural(card.members().size(), "membre", "membres"),
                Format.plural(withAccess, "compte avec accès", "comptes avec accès"),
                card.progress().isEmpty() ? "Configuration non commencée" : "Guide : " + card.progress().stream().map(entry -> entry.user().name() + " " + entry.progress().percent() + " %").collect(java.util.stream.Collectors.joining(", ")),
                members, steps, addMember, rights(card, activeUsers, csrf), progress(card.progress()), rename, delete);
    }

    /**
     * Tableau des droits d'un foyer : une ligne par compte actif, une colonne par membre, puis « Totaux du foyer » et
     * « Immobilier ». Une case cochee donne l'acces complet (voir et modifier).
     */
    static Html rights(HouseholdCard card, List<User> activeUsers, String csrf) {
        if (activeUsers.isEmpty()) return Html.raw("<p class=\"muted\">Aucun compte pour l'instant : créez-le dans Utilisateurs, puis cochez ici ce qu'il voit.</p>");
        long household = card.household().id();
        List<String> headers = new java.util.ArrayList<>();
        headers.add("Compte");
        card.members().forEach(member -> headers.add(member.name() + (member.account() && !member.name().toLowerCase(java.util.Locale.ROOT).contains("joint") ? " (compte joint)" : "")));
        headers.add("Totaux du foyer");
        headers.add("Immobilier");
        Html head = headers.stream().map(header -> Html.of("<th scope=\"col\">%s</th>", header)).collect(Html.joining());
        Html rows = activeUsers.stream().map(user -> {
            Access access = card.accesses().get(user);
            String prefix = "u" + user.id() + "_";
            Html cells = Html.concat(
                    card.members().stream().map(member -> box(prefix + "member_" + member.id(), user.name() + " : " + member.name(),
                            access != null && access.members().contains(member.id()))).collect(Html.joining()),
                    box(prefix + "whole", user.name() + " : totaux du foyer", access != null && access.wholeHousehold()),
                    box(prefix + "estate", user.name() + " : immobilier", access != null && access.realEstate()));
            // Seuls les comptes du foyer sont affiches ; les autres attendent, caches, qu'on les ajoute.
            boolean member = access != null && access.any();
            return Html.of("<tr%s><th scope=\"row\">%s <span class=\"muted small\">%s</span></th>%s</tr>",
                    Html.raw(member ? "" : " hidden data-candidate=\"" + user.id() + "\""), user.name(), user.login(), cells);
        }).collect(Html.joining());
        List<User> outside = activeUsers.stream().filter(user -> card.accesses().get(user) == null || !card.accesses().get(user).any()).toList();
        Html add = outside.isEmpty() ? Html.EMPTY : Html.of("""
                <label class="add-account">Ajouter un compte à ce foyer <select data-add-account><option value="">Choisir un compte…</option>%s</select></label>""",
                outside.stream().map(user -> Html.of("<option value=\"%s\">%s (%s)</option>", user.id(), user.name(), user.login())).collect(Html.joining()));
        boolean empty = outside.size() == activeUsers.size();
        return Html.of("""
                <form method="post" action="/administration/foyers/%s/droits" class="rights-form">%s
                %s<div class="table-wrap"><table class="rights-table"><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>%s
                <ul class="muted small rights-legend">
                <li><b>Une colonne de membre</b> : ses revenus, dépenses et virements, à voir et modifier. Une ligne partagée avec un membre non coché
                reste visible mais en lecture seule.</li>
                <li><b>Totaux du foyer</b> : le total de tous les membres (les lignes des membres non cochés y apparaissent « Ligne masquée ») et les
                réglages communs du foyer (Guide de saisie, impôts du foyer).</li>
                <li><b>Immobilier</b> : les biens, prêts, apports, charges et la répartition, à voir et modifier.</li>
                <li>Aucune case cochée : le compte n'a pas accès à ce foyer.</li></ul>
                <div class="form-actions"><button type="submit">Enregistrer les droits</button></div></form>""",
                household, Components.csrfField(csrf),
                empty ? Html.raw("<p class=\"muted\">Aucun compte n'a encore accès à ce foyer : ajoutez-en un ci-dessous, cochez ses cases, puis enregistrez.</p>") : Html.EMPTY,
                head, rows, add);
    }

    private static Html box(String name, String label, boolean checked) {
        return Html.of("<td class=\"center\"><input type=\"checkbox\" name=\"%s\" value=\"on\" aria-label=\"%s\"%s></td>", name, label,
                Html.raw(checked ? " checked" : ""));
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
                    entry.user().name(), Html.EMPTY, step.percent(), step.percent(),
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
                         "Comptes de connexion et accès aux foyers. Les changements d'accès sont visibles par les personnes concernées et journalisés dans Sécurité.",
                        Html.EMPTY),
                Components.notice(notice),
                Components.card("Utilisateurs", users.size(), Html.EMPTY, users.isEmpty() ? Html.raw("<p class=\"empty\">Aucun utilisateur.</p>")
                        : Components.table(List.of("Nom", "Identifiant", "État", "Foyers", ""), rows, false, false)),
                Components.disclosure("Créer un compte de connexion", Html.concat(Html.raw("<p class=\"muted\">Ce compte permet d'ouvrir Patria. Vous pouvez le lier à un membre du foyer, puis lui attribuer ses accès et son rôle gestionnaire.</p>"), form), !values.isEmpty() || notice != null && notice.error()));
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

    static Html user(User user, List<HouseholdAccess> households, boolean provisional, java.time.LocalDate twoFactorOn, Notice notice, String csrf) {
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
                        <p class="muted">Ce que ce compte voit dans chaque foyer. Les droits se règlent dans le tableau « Qui voit quoi » de
                        chaque foyer (Administration → Foyers).</p>%s</section>""", accesses),
                Components.card("Mot de passe", null, Html.EMPTY, reset),
                Components.card("Double authentification", null, Html.EMPTY, twoFactorOn == null
                        ? Html.raw("<p class=\"muted\">Non activée. L'utilisateur l'active lui-même dans Mon compte.</p>")
                        : Html.of("""
                        <p><span class="badge paid">Activée</span> depuis le %s.</p>
                        <form method="post" action="/administration/utilisateur/%s/double-authentification/retirer" data-confirm="%s">%s\
                        <button class="danger" type="submit">Retirer sa double authentification</button></form>
                        <p class="muted small">Seulement s'il a perdu son téléphone. C'est inscrit au journal et Patria le lui signale à sa prochaine
                        connexion.</p>""", twoFactorOn.equals(java.time.LocalDate.MIN) ? "?" : twoFactorOn.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        user.id(), "Retirer la double authentification de " + user.name() + " ? Il pourra se connecter avec son seul mot de passe.",
                        Components.csrfField(csrf))),
                Components.dangerZone("Supprimer cet utilisateur", "Supprime le compte et ses droits. Les données des foyers ne changent pas.",
                        "/administration/utilisateur/" + user.id() + "/supprimer", "Supprimer l'utilisateur « " + user.name() + " » ?", "Supprimer l'utilisateur", csrf));
        return Layout.adminSection(user.name() + " · Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    /** Droits de ce compte dans un foyer, en lecture : ils se reglent dans le tableau des droits du foyer. */
    private static Html accessForm(User user, HouseholdAccess entry, String action, String csrf) {
        Access access = entry.access().orElse(null);
        return Html.of("""
                        <div class="access-form stack" id="utilisateur-%s"><div class="access-head"><h3>%s</h3><p class="access-summary %s">%s</p></div>
                        <p><a class="button secondary" href="/administration/foyers#foyer-%s">Modifier dans le tableau des droits du foyer</a></p></div>""",
                user.id(), entry.household().name(), access == null ? "off" : "on", summary(access, entry.members()), entry.household().id());
    }

    /** Ce que l'utilisateur peut faire dans ce foyer, en une phrase. */
    private static String summary(Access access, List<HouseholdDirectory.Member> members) {
        if (access == null || !access.any()) return "Pas d'accès à ce foyer.";
        List<String> names = members.stream().filter(member -> access.members().contains(member.id())).map(HouseholdDirectory.Member::name).toList();
        StringBuilder text = new StringBuilder();
        text.append(names.isEmpty() ? "Aucun membre. " : "Voit et modifie : " + String.join(", ", names) + ". ");
        text.append(access.wholeHousehold() ? "Totaux du foyer et réglages communs. " : "Pas les totaux du foyer. ");
        text.append(access.realEstate() ? "Immobilier : voit et modifie les biens." : "Pas l'immobilier.");
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

    /** Double authentification et dernieres connexions, pour « Mon compte ». */
    record Security(TwoFactorStore.TwoFactor twoFactor, boolean exposed, List<SecurityLog.LoginEvent> events, Map<String, String> places) {
    }

    static Html account(String name, String login, boolean admin, Security security, Notice notice, String csrf) {
        Html body = admin ? Html.raw("""
                <p class="muted">Vous êtes connecté en administrateur. Ce mot de passe est défini dans l'environnement du serveur (APP_PASSWORD) :
                il se change dans la configuration (compose.yaml), puis en redémarrant Patria.</p>""")
                : Components.form("/compte/mot-de-passe", csrf, false, Components.fields(3,
                        password("Mot de passe actuel", "current", "current-password", null),
                        password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères."),
                        password("Confirmation", "confirm", "new-password", null)), "Changer le mot de passe");
        Html content = Html.concat(Components.heading("Mon compte", name, login, Html.EMPTY), Components.notice(notice),
                Components.card("Mot de passe", null, Html.EMPTY, body),
                Components.card("Double authentification", null, Html.EMPTY, twoFactor(security, admin, csrf)),
                connections(security));
        return Layout.page("Mon compte · Patria", Layout.Section.ACCOUNT, csrf, content);
    }

    private static Html twoFactor(Security security, boolean admin, String csrf) {
        TwoFactorStore.TwoFactor state = security.twoFactor();
        if (state.enabled()) {
            return Html.of("""
                    <p><span class="badge paid">Activée</span> depuis le %s : à la connexion, Patria demande aussi le code de votre application
                    d'authentification (sauf sur un appareil retenu 30 jours).</p>
                    <form method="post" action="/compte/double-authentification/desactiver" class="row-form" data-confirm="Désactiver la double authentification ?">%s\
                    <label>Code actuel<input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required></label>\
                    <button class="secondary" type="submit">Désactiver</button></form>""",
                    state.enabledOn() == null || state.enabledOn().equals(java.time.LocalDate.MIN) ? "?" : state.enabledOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    Components.csrfField(csrf));
        }
        Html recommended = admin && security.exposed() ? Html.raw("""
                <p class="notice error"><b>Fortement recommandé</b> : Patria est accessible depuis Internet. Avec le seul mot de passe, quiconque le
                devine ou le vole ouvre l'administration.</p>""") : Html.EMPTY;
        return Html.concat(recommended, Html.raw("""
                <p>En plus du mot de passe, un code à 6 chiffres affiché par une application sur votre téléphone (Google Authenticator, Aegis,
                Bitwarden, 2FAS…). Un mot de passe volé ne suffit plus pour entrer.</p>
                <a class="button" href="/compte/double-authentification">Activer la double authentification</a>"""));
    }

    /** Dernieres connexions du compte, reussies ou non (lien « Derniere connexion » de l'en-tete). */
    private static Html connections(Security security) {
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        Html rows = security.events().stream().map(event -> Html.of("<tr><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>",
                event.at().format(when), event.success() ? Html.raw("<span class=\"badge paid\">Réussie</span>")
                        : Html.of("<span class=\"badge warn\">%s</span>", event.login().endsWith("(code 2FA)") ? "Code faux" : event.login().endsWith("(bloqué)") ? "Bloquée" : "Échec"),
                event.ip(), security.places().getOrDefault(event.ip(), ""))).collect(Html.joining());
        return Html.of("<section class=\"card section\" id=\"connexions\"><div class=\"section-title\"><h2>Vos dernières connexions</h2></div>%s%s</section>",
                security.events().isEmpty() ? Html.raw("<p class=\"empty\">Aucune connexion enregistrée.</p>")
                        : Components.table(List.of("Date", "Résultat", "Adresse IP", "Lieu"), rows, false, false),
                Html.raw("<p class=\"muted small\">Une connexion que vous ne reconnaissez pas ? Changez votre mot de passe et activez la double authentification.</p>"));
    }

    /** Activation : QR code a scanner, cle a saisir a la main, puis le premier code. */
    static Html twoFactorSetup(String uri, String secret, Notice notice, String csrf) {
        Html content = Html.concat(Components.backLink("/compte", "Mon compte"),
                Components.heading("Mon compte", "Activer la double authentification", "Trois étapes, une minute.", Html.EMPTY), Components.notice(notice),
                Html.of("""
                        <section class="card section two-factor-setup"><ol class="steps help-steps">
                        <li><b>Installez une application d'authentification</b> sur votre téléphone si vous n'en avez pas : Aegis ou 2FAS (Android),
                        Google Authenticator, Microsoft Authenticator, ou celle de Bitwarden.</li>
                        <li><b>Scannez ce code</b> avec l'application (« + », puis « Scanner un QR code ») :<div class="qr-box">%s</div>
                        <p class="muted">Ou tapez la clé à la main : <code class="secret">%s</code></p></li>
                        <li><b>Tapez le code à 6 chiffres</b> que l'application affiche maintenant pour Patria :
                        <form method="post" action="/compte/double-authentification/activer" class="row-form">%s\
                        <label>Code<input name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required autofocus></label>\
                        <button type="submit">Activer</button></form></li></ol>
                        <p class="muted small">Téléphone perdu ou changé : l'administrateur peut retirer votre double authentification (c'est inscrit au journal
                        et Patria vous le signale). Le QR code est dessiné par Patria : la clé ne quitte pas votre serveur.</p></section>""",
                        fr.patria.ui.QrCode.svg(uri, "QR code à scanner avec l'application d'authentification"), fr.patria.common.Totp.grouped(secret),
                        Components.csrfField(csrf)));
        return Layout.page("Double authentification · Patria", Layout.Section.ACCOUNT, csrf, content);
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
