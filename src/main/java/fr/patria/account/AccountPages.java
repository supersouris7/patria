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

    /** Un foyer et son contenu, pour la page Foyers : ses membres et les utilisateurs qui y ont acces. */
    record HouseholdCard(Household household, List<HouseholdDirectory.Member> members, List<String> users) {
    }

    /**
     * Page Foyers : une carte par foyer (renommer, membres avec ajout direct, utilisateurs, « Gerer ce foyer »),
     * {@code current} : foyer affiche. Un foyer vide montre les etapes a suivre.
     */
    static Html households(List<HouseholdCard> cards, long current, Map<String, String> values, Notice notice, String csrf) {
        Html blocks = cards.stream().map(card -> householdCard(card, current, cards.size(), csrf)).collect(Html.joining());
        Html form = Components.form("/administration/foyers", csrf, false, Components.fields(2,
                Components.input(Input.of("Nom du foyer", "name", Field.TEXT).required().maxLength(60).placeholder("Foyer Martin"), values)), "Créer le foyer");
        Html content = Html.concat(Components.heading("Administration", "Foyers",
                        "Un foyer d'abord, puis ce qu'il contient : ses membres, leurs revenus et dépenses, ses biens. Chaque foyer est séparé des autres.", Html.EMPTY),
                Components.notice(notice),
                Components.card("Foyers", cards.size(), Html.EMPTY, blocks),
                Components.card("Nouveau foyer", null, Html.EMPTY, form));
        return Layout.adminSection("Foyers · Patria", csrf, Layout.AdminSection.HOUSEHOLDS, content);
    }

    private static Html householdCard(HouseholdCard card, long current, int count, String csrf) {
        Household household = card.household();
        boolean shown = household.id() == current;
        Html members = card.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre pour l'instant.</p>")
                : Html.of("<div class=\"chips-line\">%s</div>", card.members().stream()
                .map(member -> Html.of("<span class=\"badge\">%s%s</span>", member.name(), member.account() ? " · compte joint" : "")).collect(Html.joining()));
        Html steps = card.members().isEmpty() ? Html.raw("""
                <ol class="steps"><li>Ajoutez ses membres ci-dessous (les personnes, puis le compte joint s'il y en a un).</li>
                <li>« Gérer ce foyer » : saisissez leurs revenus, les dépenses, les biens.</li>
                <li>Ouvrez-le à des utilisateurs (Utilisateurs → un utilisateur → Foyers et droits).</li></ol>""") : Html.EMPTY;
        Html addMember = Html.of("""
                <form method="post" action="/administration/foyers/%s/membres" class="row-form">%s%s%s<button class="secondary" type="submit">Ajouter le membre</button></form>""",
                household.id(), Components.csrfField(csrf),
                Components.input(Input.of("Nouveau membre", "name", Field.TEXT).required().maxLength(60).placeholder("Prénom, ou « Compte joint »"), Map.of()),
                Components.select("Type", "kind", List.of(new Components.Option("PERSON", "Personne"), new Components.Option("ACCOUNT", "Compte joint")), "PERSON"));
        Html manage = Html.of("""
                <form method="post" action="/foyer/choisir">%s<input type="hidden" name="foyer" value="%s"><input type="hidden" name="suite" value="/administration/membres">                <button type="submit"%s>%s</button></form>""", Components.csrfField(csrf), household.id(),
                Html.raw(shown ? " class=\"secondary\"" : ""), shown ? "Foyer affiché : modifier son contenu" : "Gérer ce foyer");
        Html rename = Html.of("""
                <form method="post" action="/administration/foyers/%s/renommer" class="rename-form">%s<input name="name" value="%s" maxlength="60" required                 aria-label="Nom du foyer"><button class="secondary" type="submit">Renommer</button></form>""", household.id(), Components.csrfField(csrf), household.name());
        Html delete = household.isPrimary() || count < 2 ? Html.EMPTY : Html.of("""
                <form method="post" action="/administration/foyers/%s/supprimer" data-confirm="Supprimer le foyer « %s » ? Sa base est mise de côté,                 ses utilisateurs n'y ont plus accès.">%s<button class="danger" type="submit">Supprimer</button></form>""", household.id(), household.name(), Components.csrfField(csrf));
        return Html.of("""
                        <section class="household-card" id="foyer-%s"><h3>%s%s</h3>
                        <p class="eyebrow">Membres</p>%s%s%s
                        <p class="eyebrow">Utilisateurs qui y ont accès</p><p class="muted">%s</p>
                        <div class="household-actions">%s%s%s</div></section>""",
                household.id(), household.name(), shown ? Html.raw(" <span class=\"badge paid\">affiché</span>") : Html.EMPTY,
                members, steps, addMember, card.users().isEmpty() ? "Aucun (l'administrateur voit tous les foyers)." : String.join(", ", card.users()),
                manage, rename, delete);
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
                password("Mot de passe initial", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères ; l'utilisateur pourra le changer."),
                memberSelect(choices, values.getOrDefault("membre", ""))),
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
                        <section class="card section"><div class="section-title"><h2>Foyers et droits</h2></div>
                        <p class="muted">Un foyer par bloc. Chaque case dit ce qu'elle ouvre ; le résumé en haut du bloc dit ce que l'utilisateur
                        peut faire aujourd'hui. Pensez à « Enregistrer les droits » pour chaque foyer.</p>%s</section>""", accesses),
                Components.card("Mot de passe", null, Html.EMPTY, reset),
                Components.dangerZone("Supprimer cet utilisateur", "Supprime le compte et ses droits. Les données des foyers ne changent pas.",
                        "/administration/utilisateur/" + user.id() + "/supprimer", "Supprimer l'utilisateur « " + user.name() + " » ?", "Supprimer l'utilisateur", csrf));
        return Layout.adminSection(user.name() + " · Utilisateurs · Patria", csrf, Layout.AdminSection.USERS, content);
    }

    private static Html accessForm(User user, HouseholdAccess entry, String csrf) {
        Access access = entry.access().orElse(null);
        Html members = entry.members().isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre dans ce foyer pour l'instant : ajoutez-les dans Foyers.</p>")
                : entry.members().stream().map(member -> option("member_" + member.id(),
                member.account() ? "Gère « " + member.name() + " »" : "C'est " + member.name(),
                (member.account() ? "Ce compte gère le compte joint « " + member.name() + " » : " : "Ce compte représente " + member.name() + " : ")
                        + "onglet « " + member.name() + " » dans Prévisionnel et Budget moyen, saisie et modification de "
                        + (member.account() ? "ses dépenses et virements." : "ses revenus, dépenses et virements."),
                access != null && access.members().contains(member.id()))).collect(Html.joining());
        return Html.of("""
                        <form method="post" action="/administration/utilisateur/%s/foyer/%s" class="access-form stack" data-access-form>%s
                        <div class="access-head"><h3>%s</h3><p class="access-summary %s">%s</p></div>
                        %s
                        <fieldset class="access-details" data-access-details><legend class="eyebrow">Ce qu'il voit</legend>%s%s
                        <p class="eyebrow">Membres : qui ce compte représente</p><div class="access-options">%s</div>
                        <p class="muted small">Une ligne qui concerne seulement ses membres cochés est modifiable. Une ligne partagée avec un membre non coché
                        (ex. « parts égales » avec une autre personne) reste visible en lecture seule. Il ne gère jamais les membres, les biens, la répartition,
                        les foyers ni les utilisateurs.</p></fieldset>
                        <div class="form-actions"><button type="submit">Enregistrer les droits</button></div></form>""",
                user.id(), entry.household().id(), Components.csrfField(csrf), entry.household().name(), access == null ? "off" : "on", summary(access, entry.members()),
                option("access", "Accès à ce foyer", "Le foyer apparaît pour lui (sélecteur de l'en-tête s'il en a plusieurs). Décoché : il ne le voit pas, les autres cases sont ignorées.",
                        access != null),
                option("whole", "Vue « Foyer » : les totaux", "Onglet « Foyer » dans Prévisionnel et Budget moyen, avec les revenus et dépenses de tous les membres, et toutes les lignes "
                        + "en lecture seule dans Administration. Ne donne aucun droit de modification.", access != null && access.wholeHousehold()),
                option("estate", "Immobilier, en lecture seule", "Menu Immobilier : biens, prêts, mensualités, prix du marché. Sans cette case, les mensualités et "
                        + "charges des biens restent comptées dans son budget, mais il n'ouvre pas les fiches.", access != null && access.realEstate()),
                members);
    }

    /** Ce que l'utilisateur peut faire dans ce foyer, en une phrase. */
    private static String summary(Access access, List<HouseholdDirectory.Member> members) {
        if (access == null) return "Pas d'accès à ce foyer.";
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
