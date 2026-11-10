// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;

/**
 * Accueil de la premiere connexion : un seul ecran, selon le role (administrateur, gestionnaire, membre) et le type
 * d'installation (locale ou cloud), puis la visite guidee de l'interface. HTML uniquement.
 */
final class WelcomePages {
    private WelcomePages() {
    }

    /** {@code manager} : gestionnaire du foyer affiche ; {@code encrypted} : bases chiffrees (DB_ENCRYPTION_KEY). */
    static Html page(String name, boolean manager, boolean administrator, boolean encrypted, String csrf) {
        Html body = administrator ? administration(name) : features(name, manager);
        Html content = Html.of("""
                <section class="welcome notice warning" aria-labelledby="welcome-title">%s%s
                <div class="welcome-actions"><form method="post" action="/bienvenue/terminer">%s<input type="hidden" name="visite" value="1">\
                <button type="submit">Visiter Patria (1 minute)</button></form>\
                <form method="post" action="/bienvenue/terminer">%s<input type="hidden" name="passer" value="1">\
                <button class="quiet" type="submit">Passer</button></form></div></section>""",
                body, privacy(administrator, encrypted), Components.csrfField(csrf), Components.csrfField(csrf));
        return Layout.page("Bienvenue · Patria", Layout.Section.NONE, csrf, content);
    }

    /** L'administrateur supervise : il ne cree pas de compte et ne voit aucune donnee financiere. */
    private static Html administration(String name) {
        return Html.of("""
                <p class="eyebrow">Première connexion</p><h1 id="welcome-title">Bienvenue, %s</h1>
                <p class="lead">Vous supervisez cette installation. Vous ne créez aucun compte et ne voyez aucune donnée financière : chacun s'inscrit
                ou est invité par le gestionnaire de son foyer, qui règle qui voit quoi.</p>
                <div class="welcome-grid">
                <div><b>Installation</b><span>Locale ou cloud, inscriptions, adresse publique, conditions et mentions légales.</span></div>
                <div><b>Foyers</b><span>Membres et gestionnaires de chaque foyer, sans montant ; un lien de gestion pour un foyer qui n'en a plus.</span></div>
                <div><b>Utilisateurs</b><span>Dépanner : mot de passe provisoire, double authentification, désactiver ou supprimer un compte.</span></div>
                <div><b>Sécurité</b><span>Connexions, blocages, envoi d'e-mails, journal des droits, état du serveur (HTTPS, chiffrement).</span></div>
                </div>""", name);
    }

    private static Html features(String name, boolean manager) {
        Html role = manager
                ? Html.raw(Layout.cloud()
                        ? "<p><b>Vous gérez votre foyer</b> : invitez votre conjoint ou vos proches et choisissez ce que chacun voit (Gérer → Mon foyer et les droits).</p>"
                        : "<p><b>Vous gérez votre foyer</b> : invitez vos proches et choisissez ce que chacun voit (Gérer → Mon foyer et les droits). "
                        + "La même installation peut accueillir d'autres foyers, chacun séparé.</p>")
                : Html.raw("<p><b>Le gestionnaire de votre foyer a choisi ce que vous voyez</b> : l'icône « i » en haut de l'écran le résume.</p>");
        return Html.of("""
                <p class="eyebrow">Première connexion</p><h1 id="welcome-title">Bienvenue sur Patria, %s</h1>
                <p class="lead">Patria rassemble le budget, l'épargne et l'immobilier de votre foyer, et vous dit où vous en serez dans les mois qui viennent.</p>
                <div class="welcome-grid">
                <div><b>Prévisionnel</b><span>Les 12 prochains mois : ce qui rentre, ce qui sort, les mois difficiles.</span></div>
                <div><b>Patrimoine</b><span>Épargne, bourse et immobilier : ce que vous possédez moins ce que vous devez.</span></div>
                <div><b>Bilan de santé</b><span>Endettement, épargne de précaution, impôts à ne pas oublier.</span></div>
                <div><b>Guide de saisie</b><span>Remplit Patria pas à pas : fichier de votre banque, IA ou à la main.</span></div>
                </div>%s""", name, role);
    }

    private static Html privacy(boolean administrator, boolean encrypted) {
        Html encryption = encrypted ? Html.raw("<li><b>Bases chiffrées</b> : sans la clé du serveur, les fichiers et leurs sauvegardes sont illisibles.</li>")
                : administrator ? Html.raw("""
                <li><b>Bases non chiffrées</b> : définissez <code>DB_ENCRYPTION_KEY</code> dans la configuration <b>avant toute saisie</b> ; conservez la clé
                séparément, sa perte est irréversible (<a href="/aide/chiffrement">pas à pas</a>).</li>""")
                : Html.raw("<li><b>Bases non chiffrées</b> : demandez à l'hébergeur d'activer le chiffrement avant de saisir des données sensibles.</li>");
        return Html.of("""
                <ul class="welcome-list"><li><b>Pas de connexion à votre banque, pas de publicité, pas de traceur</b>.</li>%s
                <li><b>L'IA est facultative</b> : Patria ne lui envoie rien ; vous choisissez ce que vous lui confiez.</li></ul>
                <p class="muted small"><a href="/confidentialite">Tout savoir sur la confidentialité</a></p>""", encryption);
    }
}
