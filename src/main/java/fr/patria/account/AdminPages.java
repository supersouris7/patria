// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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

/**
 * Pages de l'administrateur : il supervise les foyers et les comptes sans voir aucune donnee financiere et sans creer
 * de compte. Il regle les inscriptions, depanne (mot de passe, double authentification, gestionnaire manquant) et
 * peut inviter une personne a creer son foyer.
 */
final class AdminPages {
    /** Une ligne de la liste des comptes : le compte, ses foyers, sa derniere connexion (90 jours gardes), sa double authentification. */
    record UserLine(AccountRepository.UserRow row, List<Access> accesses, java.time.LocalDateTime lastLogin, boolean twoFactor) {
    }

    /** Une ligne de la liste des foyers : comptes qui y ont acces (gestionnaires marques). */
    record HouseholdLine(Household household, java.time.LocalDate createdOn, Map<User, Access> accesses) {
        boolean managed() {
            return accesses.entrySet().stream().anyMatch(entry -> entry.getKey().active() && entry.getValue().manager());
        }
    }

    /** Recherche, filtre, tri et page d'une liste. */
    record Listing(String query, String filter, String sort, int page, int pages, int total) {
        static final int SIZE = 50;

        String href(String path, int target) {
            StringBuilder href = new StringBuilder(path).append("?page=").append(target);
            if (!query.isEmpty()) href.append("&q=").append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
            if (!filter.isEmpty()) href.append("&filtre=").append(filter);
            if (!sort.isEmpty()) href.append("&tri=").append(sort);
            return href.toString();
        }
    }

    private AdminPages() {
    }

    // ---- Utilisateurs ----

    /** Tuiles de la page : {@code total}, actifs 30 jours, nouveaux ce mois, sans double authentification, desactives. */
    record UserStats(int total, int active30, int newThisMonth, int withoutTwoFactor, int disabled) {
    }

    static Html users(UserStats stats, List<UserLine> lines, Listing listing, String mode, Notice notice, String csrf) {
        Html tiles = Components.metrics(4,
                Components.tile("Comptes", null, String.valueOf(stats.total()), Format.plural(stats.disabled(), "désactivé", "désactivés")),
                Components.tile("Actifs 30 jours", "Au moins une connexion réussie ces 30 derniers jours.", String.valueOf(stats.active30()), "comptes"),
                Components.tile("Nouveaux ce mois", null, String.valueOf(stats.newThisMonth()), "comptes créés"),
                Components.tile("Sans double authentification", "Comptes actifs sans code de connexion : à encourager.", String.valueOf(stats.withoutTwoFactor()), "comptes actifs"));
        Html filters = Html.of("""
                <form method="get" action="/administration/utilisateurs" class="list-filters" role="search">\
                <label>Rechercher<input type="search" name="q" value="%s" placeholder="Nom, identifiant ou e-mail"></label>\
                <label>Afficher<select name="filtre">%s</select></label><label>Trier par<select name="tri">%s</select></label>\
                <button class="secondary" type="submit">Filtrer</button></form>""", listing.query(),
                options(listing.filter(), new String[][]{{"", "Tous les comptes"}, {"actifs", "Actifs"}, {"desactives", "Désactivés"}, {"sans2fa", "Sans double authentification"},
                        {"inactifs", "Sans connexion depuis 90 jours"}, {"sansfoyer", "Sans foyer"}}),
                options(listing.sort(), new String[][]{{"", "Nom"}, {"recents", "Plus récents"}, {"connexion", "Dernière connexion"}}));
        Html rows = lines.stream().map(line -> {
            User user = line.row().user();
            return Html.of("""
                    <tr><td class="select"><input type="checkbox" name="u_%s" value="on" form="groupe" aria-label="Sélectionner %s"></td>\
                    <td><a href="/administration/utilisateur/%s"><b>%s</b></a><small>%s%s</small></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>""",
                    user.id(), user.name(), user.id(), user.name(), user.login(), line.row().email() == null ? "" : " · " + line.row().email(),
                    line.accesses().isEmpty() ? Html.raw("<span class=\"muted\">Aucun foyer</span>") : Html.of("%s", line.accesses().stream()
                            .map(access -> access.householdName() + (access.manager() ? " ★" : "")).reduce((a, b) -> a + ", " + b).orElse("")),
                    line.lastLogin() == null ? "—" : line.lastLogin().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    line.twoFactor() ? Html.raw("<span class=\"badge paid\">Oui</span>") : Html.raw("<span class=\"muted\">Non</span>"),
                    user.active() ? Html.raw("<span class=\"badge paid\">Actif</span>") : Html.raw("<span class=\"badge\">Désactivé</span>"));
        }).collect(Html.joining());
        Html bulk = Html.of("""
                <form id="groupe" method="post" action="/administration/utilisateurs/groupe" class="bulk-actions" data-confirm="Appliquer cette action aux comptes cochés ?">%s\
                <label>Comptes cochés<select name="action"><option value="desactiver">Désactiver</option><option value="reactiver">Réactiver</option>\
                <option value="supprimer">Supprimer</option></select></label><button class="secondary" type="submit">Appliquer</button></form>""", Components.csrfField(csrf));
        Html table = lines.isEmpty() ? Html.raw("<p class=\"empty\">Aucun compte ne correspond.</p>") : Html.of("""
                %s<div class="table-wrap"><table class="data admin-list"><thead><tr><th></th><th>Compte</th><th>Foyers (★ gestionnaire)</th><th>Dernière connexion</th>\
                <th>2FA</th><th>État</th></tr></thead><tbody>%s</tbody></table></div>%s""", bulk, rows, pagination("/administration/utilisateurs", listing, "compte", "comptes"));
        Html signup = Html.of("""
                <form method="post" action="/administration/inscriptions" class="stack">%s%s%s%s
                <div class="form-actions"><button class="secondary" type="submit">Enregistrer</button></div></form>""", Components.csrfField(csrf),
                option(mode, "open", "Ouvertes", "Chacun crée son compte et son propre foyer depuis la page de connexion (inscriptions publiques)."),
                option(mode, "invite", "Sur invitation", "Un compte se crée avec un lien : celui d'un gestionnaire pour rejoindre son foyer, ou le vôtre pour créer un "
                         + "foyer (inscriptions privées)."),
                option(mode, "closed", "Fermées", "Plus aucun nouveau compte, même invité ; seuls vos liens « créer son foyer » restent possibles."));
        String modeLabel = mode.equals("open") ? "ouvertes" : mode.equals("closed") ? "fermées" : "sur invitation";
        Html content = Html.concat(Components.heading("Administration", "Utilisateurs",
                        "Les comptes se créent par inscription ou invitation ; vous les supervisez et les dépannez. Aucune donnée des foyers ici.", Html.EMPTY),
                Components.notice(notice), tiles, Components.disclosure("Inscriptions : " + modeLabel, signup, false),
                Components.card("Comptes", listing.total(), Html.EMPTY, Html.concat(filters, table)));
        return Layout.adminSection("Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    // ---- Foyers ----

    static Html households(List<HouseholdLine> lines, Listing listing, int total, int unmanaged, int newThisMonth, List<AccountRepository.Invitation> invitations,
                           List<HouseholdDirectory.Archive> archives, boolean recommendTwoFactor, String link, Notice notice, String csrf) {
        Html tiles = Components.metrics(3,
                Components.tile("Foyers", null, String.valueOf(total), Format.plural(newThisMonth, "créé ce mois", "créés ce mois")),
                Components.tile("Sans gestionnaire", "Personne ne peut y régler les membres et les droits : nommez un gestionnaire ou créez un lien de gestion.",
                        String.valueOf(unmanaged), unmanaged == 0 ? "tout va bien" : "à dépanner"),
                Components.tile("Liens en attente", "Invitations « créer son foyer » pas encore utilisées.", String.valueOf(invitations.size()), "valables 7 jours"));
        Html filters = Html.of("""
                <form method="get" action="/administration/foyers" class="list-filters" role="search">\
                <label>Rechercher<input type="search" name="q" value="%s" placeholder="Nom du foyer ou d'un compte"></label>\
                <label>Afficher<select name="filtre">%s</select></label><button class="secondary" type="submit">Filtrer</button></form>""", listing.query(),
                options(listing.filter(), new String[][]{{"", "Tous les foyers"}, {"sansgestionnaire", "Sans gestionnaire"}, {"sanscompte", "Sans compte"}}));
        Html rows = lines.stream().map(line -> {
            List<Map.Entry<User, Access>> active = line.accesses().entrySet().stream().filter(entry -> entry.getKey().active()).toList();
            String managers = active.stream().filter(entry -> entry.getValue().manager()).map(entry -> entry.getKey().name()).reduce((a, b) -> a + ", " + b).orElse("");
            return Html.of("""
                    <tr><td><a href="/administration/foyer/%s"><b>%s</b></a></td><td class="num">%s</td><td>%s</td><td>%s</td></tr>""",
                    line.household().id(), line.household().name(), active.size(),
                    managers.isEmpty() ? Html.raw("<span class=\"badge warn\">Sans gestionnaire</span>") : Html.text(managers),
                    line.createdOn() == null ? "—" : line.createdOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }).collect(Html.joining());
        Html table = lines.isEmpty() ? Html.raw("<p class=\"empty\">Aucun foyer ne correspond.</p>") : Html.of("""
                <div class="table-wrap"><table class="data admin-list"><thead><tr><th>Foyer</th><th class="num">Comptes</th><th>Gestionnaires</th><th>Créé le</th></tr></thead>\
                <tbody>%s</tbody></table></div>%s""", rows, pagination("/administration/foyers", listing, "foyer", "foyers"));
        Html invite = Html.of("""
                <form method="post" action="/administration/foyers/lien" class="row-form">%s<label>E-mail (facultatif)<input name="email" type="email" maxlength="254"></label>\
                <button class="secondary" type="submit">Créer le lien</button></form>%s""", Components.csrfField(csrf),
                invitations.isEmpty() ? Html.EMPTY : Html.of("<p class=\"eyebrow\">En attente</p><ul class=\"plain-list\">%s</ul>", invitations.stream().map(invitation -> Html.of("""
                        <li>Nouveau foyer%s · jusqu'au %s <form method="post" action="/administration/foyers/lien/%s/annuler" class="pending-form">%s\
                        <button class="quiet small" type="submit">Annuler</button></form></li>""", invitation.email() == null ? "" : " (" + invitation.email() + ")",
                        date(invitation.expiresAt()), invitation.id(), Components.csrfField(csrf))).collect(Html.joining())));
        Html content = Html.concat(Components.heading("Administration", "Foyers",
                        "Chaque foyer se gère lui-même : ses gestionnaires invitent les membres et règlent qui voit quoi. Vous supervisez, sans voir aucune donnée.",
                        Html.EMPTY),
                Components.notice(notice), recommendTwoFactor ? Html.raw("""
                        <p class="notice error"><b>Double authentification fortement recommandée</b> : Patria est accessible depuis Internet.
                        <a href="/compte/double-authentification">L'activer pour le compte administrateur</a></p>""") : Html.EMPTY,
                link == null ? Html.EMPTY : Html.of("<p class=\"notice success invite-link\"><label>Lien à transmettre<input value=\"%s\" readonly></label></p>", link),
                tiles, Components.card("Foyers", listing.total(), Html.EMPTY, Html.concat(filters, table)),
                Components.disclosure("Inviter une personne à créer son foyer", Html.concat(Html.raw("""
                        <p class="muted">Un lien valable 7 jours : la personne crée son compte et son foyer, dont elle devient gestionnaire. Utile quand les
                        inscriptions sont « sur invitation » ou fermées.</p>"""), invite), false),
                archives(archives, csrf));
        return Layout.adminSection("Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    /** Fiche d'un foyer : membres, comptes (gestionnaires marques), depannage s'il n'a plus de gestionnaire, suppression. */
    static Html household(HouseholdLine line, List<HouseholdDirectory.Member> members, int count, String link, Notice notice, String csrf) {
        Household household = line.household();
        List<Map.Entry<User, Access>> active = line.accesses().entrySet().stream().filter(entry -> entry.getKey().active()).toList();
        Html accounts = active.isEmpty() ? Html.raw("<p class=\"muted\">Aucun compte.</p>") : Html.of("<ul class=\"plain-list\">%s</ul>", active.stream()
                .map(entry -> Html.of("<li><a href=\"/administration/utilisateur/%s\">%s</a>%s · %s</li>", entry.getKey().id(), entry.getKey().name(),
                        entry.getValue().manager() ? " · gestionnaire" : "", AudienceText.summary(entry.getValue(), members))).collect(Html.joining()));
        Html repair = line.managed() ? Html.EMPTY : Html.of("""
                <div class="notice warning"><b>Sans gestionnaire</b> : personne ne peut régler ses membres ni ses droits.%s
                <form method="post" action="/administration/foyers/%s/lien" class="inline-form">%s<button class="secondary" type="submit">Créer un lien de gestion</button></form></div>""",
                active.isEmpty() ? Html.EMPTY : Html.of("""
                        <form method="post" action="/administration/foyers/%s/gestionnaire" class="row-form">%s%s<button type="submit">Nommer gestionnaire</button></form>""",
                        household.id(), Components.csrfField(csrf), Components.select("Compte", "compte", active.stream()
                                .map(entry -> new Components.Option(String.valueOf(entry.getKey().id()), entry.getKey().name() + " (" + entry.getKey().login() + ")")).toList(),
                                String.valueOf(active.getFirst().getKey().id()))),
                household.id(), Components.csrfField(csrf));
        Html delete = count < 2 ? Html.raw("<p class=\"muted small\">Seul foyer de l'installation : il ne peut pas être supprimé.</p>")
                : Components.dangerZone("Supprimer ce foyer", "Ses comptes perdent l'accès ; sa base est mise de côté sur le serveur, effaçable ensuite dans Foyers.",
                "/administration/foyers/" + household.id() + "/supprimer", "Supprimer le foyer « " + household.name() + " » ?", "Supprimer le foyer", csrf);
        Html content = Html.concat(Components.backLink("/administration/foyers", "Foyers"),
                Components.heading("Foyer", household.name(), line.createdOn() == null ? null : "Créé le " + line.createdOn().format(java.time.format.DateTimeFormatter
                        .ofPattern("dd/MM/yyyy")), Html.EMPTY),
                Components.notice(notice),
                link == null ? Html.EMPTY : Html.of("<p class=\"notice success invite-link\"><label>Lien à transmettre<input value=\"%s\" readonly></label></p>", link),
                repair,
                Components.card("Membres du budget", members.size(), Html.EMPTY, members.isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre.</p>")
                        : Html.of("<div class=\"chips-line\">%s</div>", members.stream().map(member -> Html.of("<span class=\"badge\">%s%s</span>", member.name(),
                        member.account() ? " · compte joint" : "")).collect(Html.joining()))),
                Components.card("Comptes", active.size(), Html.EMPTY, accounts), delete);
        return Layout.adminSection(household.name() + " · Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    private static Html pagination(String path, Listing listing, String singular, String plural) {
        if (listing.pages() <= 1) return Html.of("<p class=\"muted small\">%s</p>", Format.plural(listing.total(), singular, plural));
        return Html.of("<nav class=\"pagination\" aria-label=\"Pages\">%s<span>Page %s sur %s · %s</span>%s</nav>",
                listing.page() > 1 ? Html.of("<a class=\"button secondary small\" href=\"%s\">‹ Précédente</a>", listing.href(path, listing.page() - 1)) : Html.EMPTY,
                listing.page(), listing.pages(), Format.plural(listing.total(), singular, plural),
                listing.page() < listing.pages() ? Html.of("<a class=\"button secondary small\" href=\"%s\">Suivante ›</a>", listing.href(path, listing.page() + 1)) : Html.EMPTY);
    }

    private static Html options(String selected, String[][] values) {
        return java.util.Arrays.stream(values).map(value -> Html.of("<option value=\"%s\"%s>%s</option>", value[0], Html.raw(value[0].equals(selected) ? " selected" : ""), value[1]))
                .collect(Html.joining());
    }

    private static Html option(String mode, String value, String label, String help) {
        return Html.of("<label class=\"check option\"><input type=\"radio\" name=\"mode\" value=\"%s\"%s><span><b>%s</b><span class=\"help\">%s</span></span></label>",
                value, Html.raw(mode.equals(value) ? " checked" : ""), label, help);
    }

    private static String date(String iso) {
        return iso.substring(0, 10).replaceAll("(\\d{4})-(\\d{2})-(\\d{2})", "$3/$2/$1");
    }

    static Html user(User user, List<Access> accesses, Map<Long, List<HouseholdDirectory.Member>> members, boolean provisional, java.time.LocalDate twoFactorOn,
                     Notice notice, String csrf) {
        Html identity = Components.form("/administration/utilisateur/" + user.id(), csrf, false, Html.concat(Components.fields(2,
                        Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60), Map.of("name", user.name())),
                        Html.of("<label>Identifiant<input value=\"%s\" disabled></label>", user.login())),
                AccountPages.checkbox("active", "Compte actif (décoché : connexion impossible)", user.active())), "Enregistrer");
        Html households = accesses.isEmpty() ? Html.raw("<p class=\"muted\">Aucun foyer.</p>") : Html.of("<ul class=\"plain-list\">%s</ul>", accesses.stream()
                .map(access -> Html.of("<li><b>%s</b>%s · %s</li>", access.householdName(), access.manager() ? " · gestionnaire" : "",
                        AudienceText.summary(access, members.getOrDefault(access.householdId(), List.of())))).collect(Html.joining()));
        Html reset = Html.concat(Html.raw(provisional
                        ? "<p class=\"muted\">Mot de passe <b>provisoire</b> : l'utilisateur devra le changer à sa prochaine connexion.</p>"
                        : "<p class=\"muted\">Mot de passe choisi par l'utilisateur. Le réinitialiser le déconnecte ; il devra en choisir un nouveau à la "
                        + "connexion suivante, et Patria lui dira que l'administrateur l'a réinitialisé.</p>"),
                Components.form("/administration/utilisateur/" + user.id() + "/mot-de-passe", csrf, false,
                        Components.fields(2, AccountPages.password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères.")),
                        "Réinitialiser le mot de passe"));
        Html content = Html.concat(Components.backLink("/administration/utilisateurs", "Utilisateurs"),
                Components.heading("Utilisateur", user.name(), user.login(), Html.EMPTY), Components.notice(notice),
                Components.card("Compte", null, Html.EMPTY, identity),
                Html.of("""
                        <section class="card section"><div class="section-title"><h2>Foyers et droits</h2></div>
                        <p class="muted">Ce que ce compte voit dans chaque foyer. Les droits sont réglés par les gestionnaires de chaque foyer.</p>%s</section>""",
                        households),
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
}
