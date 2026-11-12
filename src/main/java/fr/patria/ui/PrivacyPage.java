// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

/**
 * Page « Confidentialite » : ce qui reste sur le serveur, ce qui en sort, comment utiliser un assistant IA sans
 * exposer ses donnees, et comment effacer. Accessible sans connexion. HTML uniquement.
 */
public final class PrivacyPage {
    private PrivacyPage() {
    }

    /** {@code offline} : mode hors ligne actif (OFFLINE=true), aucun appel externe ; {@code encrypted} : bases chiffrees. */
    public static Html render(boolean offline, boolean encrypted, String csrf) {
        Html external = offline
                ? Html.raw("<p class=\"notice success\">Le mode hors ligne est actif (OFFLINE=true) : Patria n'appelle aucun service extérieur. "
                + "Pas de prix du marché, d'actualisation des estimations ni de taux du marché.</p>")
                : Html.raw("""
                <table class="data privacy-table"><thead><tr><th>Service</th><th>Ce qui est envoyé</th><th>Quand</th></tr></thead><tbody>
                <tr><td>API Adresse (data.gouv.fr, État)</td><td>l'adresse ou la commune d'un bien</td><td>calcul du prix du marché, au plus une fois par mois et par bien</td></tr>
                <tr><td>Fichiers DVF (data.gouv.fr, État)</td><td>le code de la commune et du département</td><td>idem</td></tr>
                <tr><td>Banque centrale européenne</td><td>rien de personnel</td><td>taux moyen des crédits, une fois par jour</td></tr>
                <tr><td>INSEE</td><td>rien de personnel</td><td>indice des prix (inflation), une fois par jour</td></tr>
                <tr><td>Yahoo Finance</td><td>le code d'une valeur de bourse (ex. AI.PA), jamais la quantité ni le montant</td><td>cours du jour des lignes saisies, au plus une fois par heure</td></tr>
                <tr><td>Have I Been Pwned</td><td>les 5 premiers caractères de l'empreinte SHA-1 d'un nouveau mot de passe (jamais le mot de passe)</td><td>à la création ou au changement d'un mot de passe, pour refuser un mot de passe déjà fuité</td></tr>
                <tr><td>ipwho.is</td><td>l'adresse IP d'une connexion (pas l'identifiant)</td><td>une fois par adresse, pour le pays et la ville sur la page Sécurité</td></tr>
                <tr><td>Google Maps, carte DVF</td><td>les coordonnées d'une vente ou d'un bien</td><td>seulement si vous cliquez sur le lien</td></tr>
                </tbody></table>
                <p class="muted">Aucun nom, revenu, dépense ni montant de prêt n'est envoyé. Pour couper ces appels : <code>OFFLINE=true</code> dans le fichier
                <code>.env</code>, puis redémarrer Patria.</p>""");
        Html content = Html.concat(
                Components.heading("Patria", "Confidentialité", "Ce que Patria garde, ce qui en sort, et comment protéger vos données.", Html.EMPTY),
                Components.card("Ce qui reste chez vous", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list privacy-list">
                        <li><b>Toutes vos données</b> (membres, revenus, dépenses, biens, prêts) sont dans des fichiers sur <b>votre</b> serveur : une base par
                        foyer, plus <code>comptes.sqlite3</code> pour les utilisateurs.</li>
                        <li><b>Aucun traceur</b>, aucune statistique d'usage, aucun service tiers dans les pages : tout est servi par Patria.</li>
                        <li><b>Deux cookies au plus</b> : la session, effacée à la déconnexion ou après 12 heures ; et, si vous cochez « Se souvenir de cet
                        appareil » à la double authentification, un jeton d'appareil gardé 30 jours (la base n'en garde que l'empreinte).</li>
                        <li><b>Double authentification</b> (Mon compte) : un code à 6 chiffres d'une application sur votre téléphone, en plus du mot de passe.
                        La clé reste sur votre serveur ; le QR code est dessiné par Patria.</li>
                        <li><b>Mots de passe hachés</b> (bcrypt). Chaque utilisateur ne voit que les foyers et les membres qu'on lui ouvre.</li>
                        <li>Les synthèses PDF sont fabriquées sur le serveur : rien n'est envoyé pour les produire.</li></ul>""")),
                Components.card("Qui voit quoi", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list privacy-list">
                        <li><b>Chacun ne voit que ce que le gestionnaire du foyer lui a coché</b> dans le tableau des droits : les membres dont il voit
                        et modifie les lignes, et, seulement si on les lui ouvre, les totaux du foyer et l'immobilier. Il ne voit pas les autres foyers.
                        Le gestionnaire règle aussi ses propres cases : gérer le foyer ne l'oblige pas à tout voir.</li>
                        <li><b>Chacun crée son compte</b> : par inscription (son propre foyer) ou par l'invitation d'un gestionnaire. <b>L'administrateur</b>
                        supervise les comptes, règle les inscriptions et surveille la sécurité ; il <b>ne crée aucun compte et ne voit aucune donnée
                        financière</b> dans l'interface.</li>
                        <li><b>Transparence</b> : chaque accès est visible par les comptes du foyer (icône « qui voit quoi » de l'en-tête, avec les
                        changements récents) et inscrit au journal. Un mot de passe réinitialisé par l'administrateur doit être changé à la connexion
                        suivante, et Patria le signale. Ce journal n'est pas une protection contre une personne qui contrôle le serveur et pourrait le
                        modifier.</li>
                        <li><b>Limite</b> : la personne qui a la main sur le serveur (fichiers, mémoire, clé de chiffrement) peut techniquement tout lire.
                        Confiez l'hébergement à quelqu'un de confiance.</li></ul>""")),
                Components.card("Ce qui sort du serveur", null, Html.EMPTY, external),
                Components.card("Les assistants IA", null, Html.EMPTY, Html.raw("""
                        <p>Un <b>assistant IA est facultatif</b> : vous pouvez préparer le CSV vous-même. Une IA (ChatGPT, Claude, Le Chat,
                        Gemini, ou un modèle local…) peut aider à transformer vos documents en fichier. <b>Patria ne lui envoie rien</b> : c'est vous qui copiez le prompt et joignez vos
                        documents, chez le service de votre choix. Ses conditions d'utilisation s'appliquent alors à ce que vous lui envoyez.
                        Tout le reste peut se saisir à la main, sans IA.</p>
                        <ol class="privacy-steps">
                        <li><b>Attention aux données confiées à une IA commerciale</b> : elle reçoit tout ce que vous lui envoyez (nom, adresse, IBAN,
                        numéros de compte, numéro fiscal, montants) et peut, selon ses conditions, le conserver ou s'en servir pour s'entraîner. Retirez
                        vous-même ce que vous ne voulez pas partager. Avec une IA locale installée par l'administrateur, le document est analysé sur votre serveur : rien ne sort.</li>
                        <li><b>Réglez l'assistant avant d'envoyer</b> : conversation temporaire, entraînement refusé, aucun lien de partage public, mémoire désactivée,
                        conversation effacée après usage. Le détail est ci-dessous.</li>
                        <li><b>Vérifiez les conditions du service</b> et désactivez l'entraînement si possible. Une conversation temporaire ne garantit pas
                        l'absence de conservation : les règles du fournisseur s'appliquent.</li>
                        <li><b>Préférez un modèle local</b> si vous en avez un (Ollama, LM Studio…) : rien ne quitte votre ordinateur.</li>
                        <li><b>Le prompt demande des libellés génériques</b> (« Mutuelle », pas « Mutuelle n° 123456 »), mais ne protège pas un original déjà
                        envoyé. Les montants, dates et libellés transmis peuvent encore identifier une personne.</li>
                        <li><b>Vérifiez l'aperçu</b> : Patria montre chaque ligne avant de l'enregistrer.</li></ol>""" + Components.AI_SETTINGS)),
                Components.card("Effacer des données", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list privacy-list">
                        <li>Supprimer une ligne, un bien ou un membre le retire de la base active ; des copies peuvent subsister dans les sauvegardes,
                        journaux SQLite ou anciens blocs du disque. Ce n'est pas un effacement physique garanti.</li>
                        <li>Un <b>foyer supprimé</b> est mis de côté sur le disque, au cas où : l'administrateur l'efface définitivement dans
                        Administration → Foyers → Foyers supprimés.</li>
                        <li>Un <b>utilisateur supprimé</b> perd ses droits et sa progression dans le guide de saisie ; les données des foyers restent.</li>
                        <li>Avant chaque mise à jour de schéma, une copie de la base est faite à côté d'elle ; seules les <b>3 dernières</b> sont gardées.
                        Les sauvegardes externes restent sous la responsabilité de l'hébergeur.</li></ul>""")),
                Components.card("Ce qui dépend de votre installation", null, Html.EMPTY, Html.of("""
                        <ul class="plain-list privacy-list">
                        <li><b>HTTPS</b> : placez Patria derrière un reverse proxy HTTPS dès qu'il est accessible hors de votre machine
                        (<a href="/aide/https">comment faire</a>).</li>
                        %s
                        <li><b>Mot de passe administrateur</b> : dans <code>.env</code>, il peut être remplacé par son empreinte <code>{bcrypt}…</code>.</li></ul>""",
                        encrypted ? Html.raw("""
                        <li><b>Disque</b> : les bases sont <b>chiffrées</b> (DB_ENCRYPTION_KEY), ainsi que leurs copies de sauvegarde et les foyers supprimés.
                        Un disque ou une sauvegarde volés sont illisibles sans la clé. <b>Gardez la clé en lieu sûr</b> : perdue, les données le sont aussi.</li>""")
                        : Html.raw("""
                        <li><b>Disque</b> : les bases <b>ne sont pas chiffrées</b> ; qui accède au serveur, au volume Docker ou à une sauvegarde peut les lire.
                        Définissez <code>DB_ENCRYPTION_KEY</code> pour les chiffrer (<a href="/aide/chiffrement">pas à pas</a>).</li>"""))));
        return Layout.page("Confidentialité · Patria", Layout.Section.NONE, csrf, content);
    }
}
