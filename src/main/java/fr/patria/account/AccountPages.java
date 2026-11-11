// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.util.List;
import java.util.Map;

/** Pages des foyers, des utilisateurs et de « Mon compte ». HTML uniquement. */
final class AccountPages {
    private AccountPages() {
    }

    // ---- Foyers ----

    /**
     * Un foyer pour la page Foyers : ses membres (noms seulement), les droits de chaque compte et l'avancement de leur
     * guide de saisie. Aucun montant.
     */
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
        return account(name, login, admin, security, null, false, notice, csrf, Html.EMPTY, Html.EMPTY);
    }

    /** {@code email} : e-mail du compte (null pour l'administrateur) ; {@code mailActive} : envoi d'e-mails regle. */
    static Html account(String name, String login, boolean admin, Security security, AccountRepository.Email email, boolean mailActive, Notice notice, String csrf,
                        Html profile, Html api) {
        Html body = admin ? Html.raw("""
                <p class="muted">Vous êtes connecté en administrateur. Ce mot de passe est défini dans l'environnement du serveur (APP_PASSWORD) :
                il se change dans la configuration (compose.yaml), puis en redémarrant Patria.</p>""")
                : Components.form("/compte/mot-de-passe", csrf, false, Components.fields(3,
                        password("Mot de passe actuel", "current", "current-password", null),
                        password("Nouveau mot de passe", "password", "new-password", "Au moins " + AccountRepository.MIN_PASSWORD + " caractères."),
                        password("Confirmation", "confirm", "new-password", null)), "Changer le mot de passe");
        Html content = Html.concat(Components.heading("Mon compte", name, login,
                        Html.of("<a class=\"button secondary\" href=\"%s?visite=1\">Revoir la visite guidée</a>", admin ? "/administration/securite" : "/")),
                Components.notice(notice), profile,
                Components.card("Mot de passe", null, Html.EMPTY, body),
                email == null ? Html.EMPTY : Components.card("E-mail", null, Html.EMPTY, emailForm(email, mailActive, csrf)),
                Components.card("Double authentification", null, Html.EMPTY, twoFactor(security, admin, csrf)),
                connections(security), api);
        return Layout.page("Mon compte · Patria", Layout.Section.ACCOUNT, csrf, content);
    }

    /**
     * Profil : photo ronde (ou initiales), prenom, nom, date de naissance, telephone, et le membre du budget que le
     * compte represente dans le foyer affiche (« Je suis Paul »).
     */
    static Html profileCard(long userId, String name, AccountRepository.Profile profile, List<HouseholdDirectory.Member> members, Long selfMember,
                            String householdName, String csrf) {
        Html options = members.stream().filter(member -> !member.account()).map(member -> Html.of("<option value=\"%s\"%s>%s</option>", member.id(),
                Html.raw(selfMember != null && selfMember == member.id() ? " selected" : ""), member.name())).collect(Html.joining());
        Html photo = Html.of("""
                <div class="profile-head">%s<div class="stack"><form method="post" action="/compte/photo" enctype="multipart/form-data" class="row-form">%s\
                <label>Photo (JPEG ou PNG, recadrée en rond)<input type="file" name="photo" accept="image/jpeg,image/png" required></label>\
                <button class="secondary" type="submit">Envoyer</button></form>%s</div></div>""",
                Components.avatar(userId, name, profile.avatar(), "avatar-large"), Components.csrfField(csrf),
                profile.avatar() ? Html.of("<form method=\"post\" action=\"/compte/photo/supprimer\">%s<button class=\"quiet\" type=\"submit\">Retirer la photo</button></form>",
                        Components.csrfField(csrf)) : Html.raw("<p class=\"muted small\">Sans photo, vos initiales s'affichent.</p>"));
        Html form = Html.of("""
                <form method="post" action="/compte/profil" class="stack">%s<div class="form-grid cols-2">\
                <label>Prénom *<input name="prenom" maxlength="60" required value="%s" autocomplete="given-name"></label>\
                <label>Nom<input name="nom" maxlength="60" value="%s" autocomplete="family-name"></label>\
                <label>Date de naissance<input type="date" name="naissance" value="%s" autocomplete="bday"></label>\
                <label>Téléphone<input type="tel" name="telephone" maxlength="20" value="%s" autocomplete="tel"><small class="muted">Visible de vous et de l'administrateur.</small></label>\
                <label>Dans le budget « %s », vous êtes<select name="membre"><option value="">Personne en particulier</option>%s</select></label></div>\
                <div class="form-actions"><button type="submit">Enregistrer le profil</button></div></form>""",
                Components.csrfField(csrf), profile.firstName() == null ? name : profile.firstName(), profile.lastName() == null ? "" : profile.lastName(),
                profile.birthDate() == null ? "" : profile.birthDate().toString(), profile.phone() == null ? "" : profile.phone(), householdName, options);
        return Html.of("<div id=\"profil\">%s</div>", Components.card("Profil", null, Html.EMPTY, Html.concat(photo, form)));
    }

    /**
     * Acces en lecture seule pour un tableau de bord (Homepage, Homarr, Dashy) : un jeton par outil, revocable, lie au
     * foyer affiche. {@code newToken} : celui qui vient d'etre cree, affiche une seule fois.
     */
    static Html apiCard(List<AccountRepository.ApiToken> tokens, String householdName, String newToken, String csrf) {
        Html created = newToken == null ? Html.EMPTY : Html.of("""
                <div class="notice success"><b>Votre jeton</b> (copiez-le maintenant, il ne sera plus affiché) :<code class="api-token">%s</code></div>""", newToken);
        Html rows = tokens.stream().map(token -> Html.of("""
                <tr><td><b>%s</b></td><td>%s</td><td>%s</td><td>%s</td><td><form method="post" action="/compte/api/%s/revoquer">%s\
                <button class="quiet small" type="submit">Révoquer</button></form></td></tr>""", token.name(), token.householdName(), token.createdAt().replace('T', ' '),
                token.lastUsedAt() == null ? "jamais" : token.lastUsedAt().replace('T', ' '), token.id(), Components.csrfField(csrf))).collect(Html.joining());
        Html list = tokens.isEmpty() ? Html.raw("<p class=\"muted\">Aucun jeton.</p>")
                : Components.table(List.of("Nom", "Foyer", "Créé le", "Dernier accès", ""), rows, false, false);
        Html body = Html.of("""
                <p class="muted">Pour afficher vos chiffres dans un tableau de bord (Homepage, Homarr, Dashy) : un jeton en <b>lecture seule</b>, pour le foyer
                « %s ». Il donne le reste à vivre du mois, le patrimoine net, l'épargne, les alertes du bilan et l'avancement du guide ; rien d'autre, et aucune
                modification possible. Traitez-le comme un mot de passe.</p>%s%s
                <form method="post" action="/compte/api" class="row-form">%s<label>Nom du jeton<input name="nom" maxlength="60" required placeholder="Homepage"></label>\
                <button class="secondary" type="submit">Créer un jeton</button></form>
                <details><summary>Exemple pour Homepage (widget customapi)</summary><pre><code>- Patria:
    href: https://votre-patria.example
    widget:
      type: customapi
      url: https://votre-patria.example/api/v1/resume
      headers:
        Authorization: Bearer patria_…
      mappings:
        - field: reste_a_vivre
          label: Reste à vivre
          format: number
          suffix: €
        - field: patrimoine_net
          label: Patrimoine net
          format: number
          suffix: €
        - field: alertes
          label: Alertes</code></pre></details>""",
                householdName, created, list, Components.csrfField(csrf));
        return Html.of("<div id=\"api\">%s</div>", Components.card("Accès API (tableau de bord)", tokens.size(), Html.EMPTY, body));
    }

    /** E-mail du compte : etat (confirme ou non) et changement, avec envoi d'un lien quand l'envoi est regle. */
    static Html emailForm(AccountRepository.Email email, boolean mailActive, String csrf) {
        Html state = email.address() == null ? Html.raw("<p class=\"muted\">Aucun e-mail. "
                + (mailActive ? "Ajoutez-en un pour pouvoir réinitialiser votre mot de passe vous-même." : "Facultatif.") + "</p>")
                : Html.of("<p><b>%s</b> %s</p>", email.address(), Html.raw(email.verified() ? "<span class=\"badge paid\">confirmé</span>"
                : mailActive ? "<span class=\"badge warn\">à confirmer</span>" : ""));
        return Html.concat(state, Html.of("""
                <form method="post" action="/compte/email" class="row-form">%s<label>%s<input name="email" type="email" maxlength="254" value="%s" required autocomplete="email"></label>                <button class="secondary" type="submit">%s</button></form>""", Components.csrfField(csrf), email.address() == null ? "E-mail" : "Nouvel e-mail",
                email.address() == null ? "" : email.address(), mailActive ? "Envoyer le lien de confirmation" : "Enregistrer"));
    }

    /** Etapes de l'arrivee d'un nouveau compte (1 a 4), en haut des pages concernees. */
    static Html onboarding(int current) {
        String[] steps = {"Compte", "E-mail", "Sécurité", "Découverte"};
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < steps.length; index++) {
            String css = index + 1 < current ? "done" : index + 1 == current ? "current" : "";
            items.append("<li class=\"").append(css).append("\"").append(index + 1 == current ? " aria-current=\"step\"" : "").append(">").append(steps[index]).append("</li>");
        }
        return Html.of("<ol class=\"onboarding-steps\" aria-label=\"Création du compte\">%s</ol>", Html.raw(items.toString()));
    }

    /** Page de confirmation de l'e-mail, exigee avant d'aller plus loin pour un compte cree quand l'administrateur l'exige. */
    static Html emailRequired(AccountRepository.Email email, Notice notice, String csrf) {
        return Layout.document("Confirmer votre e-mail · Patria", "login-page", Html.of("""
                <main class="login-card">%s%s<h1 class="login-title">Confirmez votre e-mail</h1>%s
                <p class="muted">L'administrateur demande un e-mail confirmé : il vous servira à réinitialiser votre mot de passe. Indiquez-le, puis ouvrez le lien
                reçu (valable 24 heures).</p>%s
                <form method="post" action="/deconnexion">%s<button class="quiet" type="submit">Se déconnecter</button></form></main>""",
                Layout.brand(), onboarding(2), Components.notice(notice), emailForm(email, true, csrf), Components.csrfField(csrf)));
    }

    /** Proposition de la double authentification, juste apres le premier mot de passe : activer, ou plus tard. */
    static Html offerTwoFactor(String csrf) {
        return Layout.document("Sécuriser votre compte · Patria", "login-page", Html.of("""
                <main class="login-card">%s%s<h1 class="login-title">Protégez votre compte</h1>
                <p>La <b>double authentification</b> demande, en plus du mot de passe, un code à 6 chiffres affiché par une application de votre téléphone
                (Google Authenticator, Microsoft Authenticator, Aegis, 2FAS…). Même si votre mot de passe est deviné ou volé, personne ne peut ouvrir votre budget.</p>
                <p class="muted">Deux minutes suffisent. Vous pourrez aussi l'activer plus tard dans Mon compte.</p>
                <div class="form-actions"><a class="button" href="/compte/double-authentification?suite=bienvenue">Activer maintenant</a>
                <form method="post" action="/compte/securiser/plus-tard">%s<button class="secondary" type="submit">Plus tard</button></form></div></main>""",
                Layout.brand(), onboarding(3), Components.csrfField(csrf)));
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
    static Html twoFactorSetup(String uri, String secret, Notice notice, boolean welcome, String csrf) {
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
                        Html.concat(Components.csrfField(csrf), welcome ? Html.raw("<input type=\"hidden\" name=\"suite\" value=\"bienvenue\">") : Html.EMPTY)));
        return Layout.page("Double authentification · Patria", Layout.Section.ACCOUNT, csrf, content);
    }

    static Html password(String label, String name, String autocomplete, String help) {
        return Html.of("<label>%s <span class=\"required-mark\">*</span><input name=\"%s\" type=\"password\" autocomplete=\"%s\" minlength=\"%s\" maxlength=\"72\" required>%s</label>",
                label, name, autocomplete, name.equals("current") ? 1 : AccountRepository.MIN_PASSWORD,
                help == null ? Html.EMPTY : Html.of("<span class=\"help\">%s</span>", help));
    }

    static Html checkbox(String name, String label, boolean checked) {
        return Html.of("<label class=\"check\"><input type=\"checkbox\" name=\"%s\" value=\"on\"%s>%s</label>", name, checked ? Html.raw(" checked") : Html.EMPTY, label);
    }
}
