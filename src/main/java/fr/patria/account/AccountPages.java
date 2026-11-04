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

    static Html households(List<Household> households, Map<Long, Integer> users, Map<String, String> values, Notice notice, String csrf) {
        Html rows = households.stream().map(household -> Html.of("""
                        <tr><td><form method="post" action="/administration/foyers/%s/renommer" class="rename-form">%s\
                        <input name="name" value="%s" maxlength="60" required aria-label="Nom du foyer"><button class="secondary" type="submit">Renommer</button></form></td>\
                        <td>%s</td><td class="num">%s</td><td>%s</td></tr>""",
                household.id(), Components.csrfField(csrf), household.name(), household.isPrimary() ? "Principal (base de départ)" : household.file(),
                users.getOrDefault(household.id(), 0),
                household.isPrimary() || households.size() < 2 ? Html.EMPTY : Html.of("""
                        <form method="post" action="/administration/foyers/%s/supprimer" data-confirm="Supprimer le foyer « %s » ? Sa base est mise de côté, \
                        ses utilisateurs n'y ont plus accès.">%s<button class="danger" type="submit">Supprimer</button></form>""",
                        household.id(), household.name(), Components.csrfField(csrf)))).collect(Html.joining());
        Html form = Components.form("/administration/foyers", csrf, false, Components.fields(2,
                Components.input(Input.of("Nom du foyer", "name", Field.TEXT).required().maxLength(60).placeholder("Foyer Martin"), values)), "Créer le foyer");
        Html content = Html.concat(Components.heading("Administration", "Foyers", "Chaque foyer a ses propres membres, revenus, dépenses, virements et biens.", Html.EMPTY),
                Components.notice(notice),
                Components.card("Nouveau foyer", null, Html.EMPTY, Html.concat(Html.raw("""
                        <p class="muted">Le foyer est vide à sa création. Choisissez-le dans l'en-tête pour y ajouter ses membres, puis ouvrez-le à des
                        utilisateurs dans Utilisateurs.</p>"""), form)),
                Components.card("Foyers", households.size(), Html.EMPTY, Components.table(List.of("Nom", "Base", "#Utilisateurs", ""), rows, false, false)));
        return Layout.adminSection("Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    // ---- Utilisateurs ----

    static Html users(List<User> users, Map<Long, List<Access>> accesses, Map<String, String> values, Notice notice, String csrf) {
        Html rows = users.stream().map(user -> Html.of("<tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>",
                user.name(), user.login(), user.active() ? Html.raw("<span class=\"badge paid\">Actif</span>") : Html.raw("<span class=\"badge\">Désactivé</span>"),
                accesses.getOrDefault(user.id(), List.of()).stream().map(Access::householdName).reduce((a, b) -> a + ", " + b).orElse("Aucun foyer"),
                Components.link("/administration/utilisateur/" + user.id(), "Modifier"))).collect(Html.joining());
        Html form = Components.form("/administration/utilisateurs", csrf, false, Components.fields(3,
                Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60).placeholder("Paul"), values),
                Components.input(Input.of("Identifiant", "login", Field.TEXT).required().maxLength(40).placeholder("paul")
                        .help("Lettres, chiffres, point, tiret. Sert à la connexion."), values),
                password("Mot de passe initial", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères ; l'utilisateur pourra le changer.")),
                "Créer l'utilisateur");
        Html content = Html.concat(Components.heading("Administration", "Utilisateurs",
                        "L'administrateur se connecte avec le compte défini dans l'environnement (APP_PASSWORD). Les utilisateurs ne voient que ce que vous leur ouvrez.",
                        Html.EMPTY),
                Components.notice(notice),
                Components.card("Nouvel utilisateur", null, Html.EMPTY, form),
                Components.card("Utilisateurs", users.size(), Html.EMPTY, users.isEmpty() ? Html.raw("<p class=\"empty\">Aucun utilisateur.</p>")
                        : Components.table(List.of("Nom", "Identifiant", "État", "Foyers", ""), rows, false, false)));
        return Layout.adminSection("Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    /** Membres d'un foyer, avec ce que l'utilisateur y voit (vide : pas d'acces). */
    record HouseholdAccess(Household household, List<HouseholdDirectory.Member> members, Optional<Access> access) {
    }

    static Html user(User user, List<HouseholdAccess> households, Notice notice, String csrf) {
        Html identity = Components.form("/administration/utilisateur/" + user.id(), csrf, false, Html.concat(Components.fields(2,
                        Components.input(Input.of("Nom affiché", "name", Field.TEXT).required().maxLength(60), Map.of("name", user.name())),
                        Html.of("<label>Identifiant<input value=\"%s\" disabled></label>", user.login())),
                checkbox("active", "Compte actif (décoché : connexion impossible)", user.active())), "Enregistrer");
        Html reset = Components.form("/administration/utilisateur/" + user.id() + "/mot-de-passe", csrf, false,
                Components.fields(2, password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères.")),
                "Réinitialiser le mot de passe");
        Html accesses = households.stream().map(entry -> accessForm(user, entry, csrf)).collect(Html.joining());
        Html content = Html.concat(Components.backLink("/administration/utilisateurs", "Utilisateurs"),
                Components.heading("Utilisateur", user.name(), user.login(), Html.EMPTY), Components.notice(notice),
                Components.card("Compte", null, Html.EMPTY, identity),
                Html.of("""
                        <section class="card section"><div class="section-title"><h2>Foyers et vues</h2></div>
                        <p class="muted">Pour chaque foyer : les vues que l'utilisateur peut ouvrir. « Foyer » montre le total de tous les membres ;
                        un membre montre sa part. Il peut ajouter et modifier les revenus, dépenses et virements qui ne concernent que ses vues ouvertes ;
                        une ligne partagée avec un autre membre reste en lecture seule.</p>%s</section>""", accesses),
                Components.card("Mot de passe", null, Html.EMPTY, reset),
                Components.dangerZone("Supprimer cet utilisateur", "Supprime le compte et ses droits. Les données des foyers ne changent pas.",
                        "/administration/utilisateur/" + user.id() + "/supprimer", "Supprimer l'utilisateur « " + user.name() + " » ?", "Supprimer l'utilisateur", csrf));
        return Layout.adminSection(user.name() + " · Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    private static Html accessForm(User user, HouseholdAccess entry, String csrf) {
        Access access = entry.access().orElse(null);
        Html members = entry.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre dans ce foyer pour l'instant.</p>")
                : entry.members().stream().map(member -> checkbox("member_" + member.id(), member.name() + (member.account() ? " (compte)" : ""),
                access != null && access.members().contains(member.id()))).collect(Html.joining());
        return Html.of("""
                        <form method="post" action="/administration/utilisateur/%s/foyer/%s" class="access-form stack">%s<h3>%s</h3>
                        %s<div class="access-grid">%s%s</div><p class="eyebrow">Membres</p><div class="access-grid">%s</div>
                        <div class="form-actions"><button type="submit">Enregistrer les droits</button></div></form>""",
                user.id(), entry.household().id(), Components.csrfField(csrf), entry.household().name(),
                checkbox("access", "Accès à ce foyer", access != null),
                checkbox("whole", "Vue « Foyer » (total)", access != null && access.wholeHousehold()),
                checkbox("estate", "Consulter l'immobilier", access != null && access.realEstate()), members);
    }

    // ---- Mon compte ----

    static Html account(String name, String login, boolean admin, Notice notice, String csrf) {
        Html body = admin ? Html.raw("""
                <p class="muted">Vous êtes connecté en administrateur. Ce mot de passe est défini dans l'environnement du serveur (APP_PASSWORD) :
                il se change dans la configuration (compose.yaml), puis en redémarrant Patria.</p>""")
                : Components.form("/compte/mot-de-passe", csrf, false, Components.fields(3,
                        password("Mot de passe actuel", "current", "current-password", null),
                        password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères."),
                        password("Confirmation", "confirm", "new-password", null)), "Changer le mot de passe");
        Html content = Html.concat(Components.heading("Mon compte", name, login, Html.EMPTY), Components.notice(notice),
                Components.card("Mot de passe", null, Html.EMPTY, body));
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
