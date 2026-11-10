package fr.patria.account;

import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.List;
import java.util.Map;

/** Page « Mon foyer » des gestionnaires : membres, qui voit quoi, invitations, nom du foyer. */
final class HouseholdPages {
    private HouseholdPages() {
    }

    static Html page(Household household, List<HouseholdDirectory.Member> members, Map<User, Access> accesses, List<AccountRepository.Invitation> invitations,
                     long self, boolean closed, Notice notice, String link, String csrf) {
        Html chips = members.isEmpty() ? Html.raw("<p class=\"muted\">Aucun membre pour l'instant.</p>")
                : Html.of("<div class=\"chips-line\">%s</div>", members.stream().map(member -> Html.of("""
                        <form method="post" action="/foyer/membre/%s/supprimer" class="badge member-chip" data-confirm="Supprimer « %s » et ses lignes propres ? \
                        Les lignes partagées gardent les autres membres.">%s%s%s<button type="submit" class="link-button" aria-label="Supprimer %s" title="Supprimer">×</button></form>""",
                        member.id(), member.name(), Components.csrfField(csrf), member.name(), member.account() ? " · compte joint" : "", member.name())).collect(Html.joining()));
        Html addMember = Html.of("""
                <form method="post" action="/foyer/membres" class="row-form">%s<label>Nouveau membre<input name="name" maxlength="60" required placeholder="Prénom, ou « Compte joint »"></label>\
                <label>Type<select name="kind"><option value="PERSON">Personne</option><option value="ACCOUNT">Compte joint</option></select></label>\
                <button class="secondary" type="submit">Ajouter</button></form>""", Components.csrfField(csrf));
        Html membersCard = Html.of("<div id=\"membres\">%s</div>", Components.card("Membres du budget", members.size(), Html.EMPTY, Html.concat(Html.raw(
                "<p class=\"muted\">Les personnes et comptes joints qui ont des revenus et des dépenses. Un membre n'est pas forcément un compte : un enfant, "
                        + "par exemple, n'en a pas.</p>"), chips, addMember)));
        Html content = Html.concat(Components.heading("Gestionnaire", household.name(), "Membres, droits et invitations de votre foyer.", Html.EMPTY),
                Components.notice(notice), link == null ? Html.EMPTY : Html.of("""
                        <p class="notice success invite-link"><label>Lien d'invitation<input value="%s" readonly></label></p>""", link),
                membersCard, rights(household, members, accesses, self, csrf), invitations(members, invitations, closed, csrf),
                Components.disclosure("Nom du foyer", Html.of("""
                        <form method="post" action="/foyer/renommer" class="row-form">%s<label>Nom<input name="name" value="%s" maxlength="60" required></label>\
                        <button class="secondary" type="submit">Renommer</button></form>""", Components.csrfField(csrf), household.name()), false),
                Components.disclosure("Supprimer le foyer", Html.of("""
                        <p class="muted">Tous ses comptes perdent l'accès à ce foyer. Sa base est mise de côté sur le serveur (l'administrateur peut l'effacer
                        définitivement ensuite) ; rien n'est récupérable depuis l'application.</p>
                        <form method="post" action="/foyer/supprimer" class="row-form">%s<label>Tapez « %s » pour confirmer<input name="confirmation" maxlength="60" required \
                        autocomplete="off"></label><button class="danger" type="submit">Supprimer définitivement le foyer</button></form>""",
                        Components.csrfField(csrf), household.name()), false));
        return Layout.adminSection("Mon foyer · Patria", csrf, Layout.AdminSection.HOUSEHOLD, content);
    }

    /** Une ligne par compte du foyer : ses membres, les totaux, l'immobilier, et s'il gere le foyer. */
    private static Html rights(Household household, List<HouseholdDirectory.Member> members, Map<User, Access> accesses, long self, String csrf) {
        StringBuilder head = new StringBuilder("<th scope=\"col\">Compte</th>");
        for (HouseholdDirectory.Member member : members) head.append("<th scope=\"col\">").append(escape(member.name())).append("</th>");
        head.append("<th scope=\"col\">Totaux du foyer</th><th scope=\"col\">Immobilier</th><th scope=\"col\">Gestionnaire</th>");
        Html rows = accesses.entrySet().stream().filter(entry -> entry.getKey().active()).map(entry -> {
            User user = entry.getKey();
            Access access = entry.getValue();
            String prefix = "u" + user.id() + "_";
            Html cells = Html.concat(members.stream().map(member -> box(prefix + "member_" + member.id(), user.name() + " : " + member.name(),
                            access.members().contains(member.id()))).collect(Html.joining()),
                    box(prefix + "whole", user.name() + " : totaux du foyer", access.wholeHousehold()),
                    box(prefix + "estate", user.name() + " : immobilier", access.realEstate()),
                    box(prefix + "manager", user.name() + " : gestionnaire", access.manager()));
            return Html.of("<tr><th scope=\"row\">%s <span class=\"muted small\">%s%s</span></th>%s</tr>", user.name(), user.login(),
                    user.id() == self ? " · vous" : "", cells);
        }).collect(Html.joining());
        return Html.of("<div id=\"droits\">%s</div>", Components.card("Qui voit quoi", null, Html.EMPTY, Html.of("""
                <form method="post" action="/foyer/droits" class="rights-form">%s
                <div class="table-wrap"><table class="rights-table"><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>
                <ul class="muted small rights-legend">
                <li><b>Une colonne de membre</b> : ses revenus, dépenses et virements, à voir et modifier. Une ligne partagée avec un membre non coché reste
                visible en lecture seule.</li>
                <li><b>Totaux du foyer</b> : le total de tous les membres (les lignes des membres non cochés y apparaissent « Ligne masquée ») et les
                réglages communs.</li>
                <li><b>Immobilier</b> : biens, prêts, apports, charges et répartition.</li>
                <li><b>Gestionnaire</b> : règle les membres, ce tableau et les invitations. Cela ne change pas ce qu'il voit : vos propres cases aussi
                se décochent (par exemple, ne pas voir les comptes de votre conjoint). Il reste toujours au moins un gestionnaire.</li>
                <li>Aucune case cochée : le compte n'a plus accès au foyer. Chacun voit le résumé de ce tableau dans l'icône « i » de l'en-tête.</li></ul>
                <div class="form-actions"><button type="submit">Enregistrer les droits</button></div></form>""",
                Components.csrfField(csrf), Html.raw(head.toString()), rows)));
    }

    /** Inviter : prenom (nouveau membre) ou membre existant, e-mail facultatif, droits ; invitations en attente. */
    private static Html invitations(List<HouseholdDirectory.Member> members, List<AccountRepository.Invitation> pending, boolean closed, String csrf) {
        Html body;
        if (closed) {
            body = Html.raw("<p class=\"muted\">Les nouveaux comptes sont fermés sur ce Patria (réglage de l'administrateur).</p>");
        } else {
            Html options = Html.concat(Html.raw("<option value=\"nouveau\">Un nouveau membre, à son prénom</option>"
                            + "<option value=\"aucun\">Aucun (il ne verra que les cases cochées ci-dessous)</option>"),
                    members.stream().map(member -> Html.of("<option value=\"%s\">%s (déjà dans le budget)</option>", member.id(), member.name())).collect(Html.joining()));
            body = Html.of("""
                    <p class="muted">La personne reçoit un lien (valable 7 jours, une seule fois) : elle crée son compte, ou utilise le sien, et arrive avec les droits
                    choisis ici. Vous pourrez les changer dans le tableau.</p>
                    <form method="post" action="/foyer/invitations" class="stack">%s
                    <div class="form-grid cols-3"><label>Dans le budget<select name="membre">%s</select></label>
                    <label>Prénom (nouveau membre)<input name="name" maxlength="60" placeholder="Camille"></label>
                    <label>E-mail (facultatif)<input name="email" type="email" maxlength="254" placeholder="camille@example.fr"></label></div>
                    <div class="check-line"><label class="check"><input type="checkbox" name="whole" value="on"> Totaux du foyer</label>
                    <label class="check"><input type="checkbox" name="estate" value="on"> Immobilier</label>
                    <label class="check"><input type="checkbox" name="manager" value="on"> Gestionnaire</label></div>
                    <div class="form-actions"><button type="submit">Créer l'invitation</button></div></form>""", Components.csrfField(csrf), options);
        }
        Html list = pending.isEmpty() ? Html.EMPTY : Html.of("<p class=\"eyebrow\">En attente</p><ul class=\"plain-list\">%s</ul>", pending.stream().map(invitation -> Html.of("""
                <li>%s%s · jusqu'au %s <form method="post" action="/foyer/invitation/%s/annuler" class="pending-form">%s<button class="quiet small" type="submit">Annuler</button></form></li>""",
                invitation.memberName() != null ? invitation.memberName() : members.stream().filter(member -> invitation.memberId() != null && member.id() == invitation.memberId())
                        .map(HouseholdDirectory.Member::name).findFirst().orElse("Invitation"),
                invitation.email() == null ? "" : " (" + invitation.email() + ")", invitation.expiresAt().substring(0, 10).replaceAll("(\\d{4})-(\\d{2})-(\\d{2})", "$3/$2/$1"),
                invitation.id(), Components.csrfField(csrf))).collect(Html.joining()));
        return Html.of("<div id=\"invitations\">%s</div>", Components.card("Inviter quelqu'un", pending.size(), Html.EMPTY, Html.concat(body, list)));
    }

    private static Html box(String name, String label, boolean checked) {
        return Html.of("<td class=\"center\"><input type=\"checkbox\" name=\"%s\" value=\"on\" aria-label=\"%s\"%s></td>", name, label, Html.raw(checked ? " checked" : ""));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
