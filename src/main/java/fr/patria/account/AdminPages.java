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
    /** Un foyer vu par l'administrateur : ses membres et les comptes qui y ont acces. */
    record HouseholdRow(Household household, List<HouseholdDirectory.Member> members, Map<User, Access> accesses) {
        boolean managed() {
            return accesses.entrySet().stream().anyMatch(entry -> entry.getKey().active() && entry.getValue().manager());
        }
    }

    private AdminPages() {
    }

    static Html households(List<HouseholdRow> rows, List<AccountRepository.Invitation> invitations, List<HouseholdDirectory.Archive> archives, boolean recommendTwoFactor,
                           String link, Notice notice, String csrf) {
        Html cards = rows.stream().map(row -> household(row, rows.size(), csrf)).collect(Html.joining());
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
                Components.card("Foyers", rows.size(), Html.EMPTY, cards),
                Components.disclosure("Inviter une personne à créer son foyer", Html.concat(Html.raw("""
                        <p class="muted">Un lien valable 7 jours : la personne crée son compte et son foyer, dont elle devient gestionnaire. Utile quand les
                        inscriptions sont « sur invitation » ou fermées.</p>"""), invite), false),
                archives(archives, csrf));
        return Layout.adminSection("Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    /** Un foyer : membres, comptes (gestionnaires marques), et le depannage s'il n'a plus de gestionnaire. */
    private static Html household(HouseholdRow row, int count, String csrf) {
        Household household = row.household();
        List<Map.Entry<User, Access>> active = row.accesses().entrySet().stream().filter(entry -> entry.getKey().active()).toList();
        Html accounts = active.isEmpty() ? Html.raw("<p class=\"muted\">Aucun compte.</p>") : Html.of("<div class=\"chips-line\">%s</div>", active.stream()
                .map(entry -> Html.of("<span class=\"badge%s\">%s%s</span>", entry.getValue().manager() ? " paid" : "", entry.getKey().name(),
                        entry.getValue().manager() ? " · gestionnaire" : "")).collect(Html.joining()));
        Html repair = row.managed() ? Html.EMPTY : Html.of("""
                <div class="notice warning"><b>Sans gestionnaire</b> : personne ne peut régler ses membres ni ses droits.%s
                <form method="post" action="/administration/foyers/%s/lien" class="inline-form">%s<button class="secondary" type="submit">Créer un lien de gestion</button></form></div>""",
                active.isEmpty() ? Html.EMPTY : Html.of("""
                        <form method="post" action="/administration/foyers/%s/gestionnaire" class="row-form">%s%s<button type="submit">Nommer gestionnaire</button></form>""",
                        household.id(), Components.csrfField(csrf), Components.select("Compte", "compte", active.stream()
                                .map(entry -> new Components.Option(String.valueOf(entry.getKey().id()), entry.getKey().name() + " (" + entry.getKey().login() + ")")).toList(),
                                String.valueOf(active.getFirst().getKey().id()))),
                household.id(), Components.csrfField(csrf));
        Html delete = household.isPrimary() || count < 2 ? Html.EMPTY : Html.of("""
                <form method="post" action="/administration/foyers/%s/supprimer" data-confirm="Supprimer le foyer « %s » ? Sa base est mise de côté,\
                 ses comptes n'y ont plus accès.">%s<button class="danger" type="submit">Supprimer le foyer</button></form>""", household.id(), household.name(),
                Components.csrfField(csrf));
        return Html.of("""
                <section class="household-card" id="foyer-%s"><details%s><summary><b>%s</b><span class="muted">%s · %s%s</span></summary>\
                <div class="disclosure-body"><p class="eyebrow">Membres</p>%s<p class="eyebrow">Comptes</p>%s%s<div class="household-actions">%s</div></div></details></section>""",
                household.id(), Html.raw(row.managed() ? "" : " open"), household.name(), Format.plural(row.members().size(), "membre", "membres"),
                Format.plural(active.size(), "compte", "comptes"), row.managed() ? "" : " · sans gestionnaire",
                row.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre.</p>") : Html.of("<div class=\"chips-line\">%s</div>", row.members().stream()
                        .map(member -> Html.of("<span class=\"badge\">%s%s</span>", member.name(), member.account() ? " · compte joint" : "")).collect(Html.joining())),
                accounts, repair, delete);
    }

    static Html users(List<User> users, Map<Long, List<Access>> accesses, String mode, Notice notice, String csrf) {
        Html rows = users.stream().map(user -> Html.of("<tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>",
                user.name(), user.login(), user.active() ? Html.raw("<span class=\"badge paid\">Actif</span>") : Html.raw("<span class=\"badge\">Désactivé</span>"),
                accesses.getOrDefault(user.id(), List.of()).stream().map(access -> access.householdName() + (access.manager() ? " (gestionnaire)" : ""))
                        .reduce((a, b) -> a + ", " + b).orElse("Aucun foyer"),
                Components.link("/administration/utilisateur/" + user.id(), "Dépanner"))).collect(Html.joining());
        Html signup = Html.of("""
                <form method="post" action="/administration/inscriptions" class="stack">%s%s%s%s
                <div class="form-actions"><button class="secondary" type="submit">Enregistrer</button></div></form>""", Components.csrfField(csrf),
                option(mode, "open", "Ouvertes", "Chacun crée son compte et son propre foyer depuis la page de connexion (installation dans le cloud, ouverte au public)."),
                option(mode, "invite", "Sur invitation", "Un compte se crée avec un lien : celui d'un gestionnaire pour rejoindre son foyer, ou le vôtre pour créer un "
                        + "foyer (auto-hébergement familial, réglage par défaut)."),
                option(mode, "closed", "Fermées", "Plus aucun nouveau compte, même invité ; seuls vos liens « créer son foyer » restent possibles."));
        Html content = Html.concat(Components.heading("Administration", "Utilisateurs",
                        "Les comptes se créent par inscription ou invitation ; vous les supervisez et les dépannez. Aucune donnée des foyers ici.", Html.EMPTY),
                Components.notice(notice), Components.card("Inscriptions", null, Html.EMPTY, signup),
                Components.card("Comptes", users.size(), Html.EMPTY, users.isEmpty() ? Html.raw("<p class=\"empty\">Aucun compte pour l'instant.</p>")
                        : Components.table(List.of("Nom", "Identifiant", "État", "Foyers", ""), rows, false, false)));
        return Layout.adminSection("Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
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
