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
                        <li><b>Un seul cookie</b>, celui de la session, effacé à la déconnexion ou après 12 heures.</li>
                        <li><b>Mots de passe chiffrés</b> (bcrypt). Chaque utilisateur ne voit que les foyers et les membres qu'on lui ouvre.</li>
                        <li>Les synthèses PDF sont fabriquées sur le serveur : rien n'est envoyé pour les produire.</li></ul>""")),
                Components.card("Qui voit quoi", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list privacy-list">
                        <li><b>Chacun ne voit que ce qui le concerne</b> : un utilisateur voit ses propres lignes (les membres qu'il représente), et seulement
                        si on les lui ouvre, les totaux du foyer et l'immobilier.</li>
                        <li><b>Un gestionnaire</b> voit et modifie tout son foyer. Il ne voit pas les autres foyers.</li>
                        <li><b>L'administrateur</b> crée les foyers et les comptes, règle les droits et surveille la sécurité. Il <b>ne voit aucune donnée
                        financière</b> : ni revenus, ni dépenses, ni biens.</li>
                        <li><b>Transparence</b> : comme il crée les comptes, l'administrateur pourrait s'en créer un et se l'ouvrir. Chaque accès est donc
                        visible par les comptes du foyer (icône jaune « qui voit quoi » à côté du foyer, avec les changements récents), et inscrit au
                        journal. Un mot de passe choisi par l'administrateur (création, réinitialisation) doit être changé à la connexion suivante, et
                        Patria le signale : il ne peut pas se connecter à votre place sans que vous le voyiez.</li>
                        <li><b>Limite</b> : la personne qui a la main sur le serveur (fichiers, mémoire, clé de chiffrement) peut techniquement tout lire.
                        Confiez l'hébergement à quelqu'un de confiance.</li></ul>""")),
                Components.card("Ce qui sort du serveur", null, Html.EMPTY, external),
                Components.card("Les assistants IA", null, Html.EMPTY, Html.raw("""
                        <p>Pour importer un échéancier de prêt ou vos relevés d'un coup, il faut un <b>assistant IA externe</b> (ChatGPT, Claude, Le Chat,
                        Gemini…) qui transforme vos documents en fichier. <b>Patria ne lui envoie rien</b> : c'est vous qui copiez le prompt et joignez vos
                        documents, chez le service de votre choix. Ses conditions d'utilisation s'appliquent alors à ce que vous lui envoyez.
                        Tout le reste peut se saisir à la main, sans IA.</p>
                        <ol class="privacy-steps">
                        <li><b>Masquez avant d'envoyer</b> : nom, adresse, IBAN et numéros de compte, numéro fiscal, numéro de sécurité sociale, numéro de
                        contrat. Un rectangle noir sur le PDF suffit, ou une capture d'écran des seules lignes utiles (dates, libellés, montants).</li>
                        <li><b>Coupez l'historique</b> : conversation temporaire, ou désactivation de l'utilisation de vos données pour l'entraînement,
                        dans les réglages de l'assistant.</li>
                        <li><b>Préférez un modèle local</b> si vous en avez un (Ollama, LM Studio…) : rien ne quitte votre ordinateur.</li>
                        <li><b>Le prompt anonymise</b> : il interdit à l'IA de recopier toute donnée identifiante et lui demande des libellés génériques
                        (« Mutuelle », pas « Mutuelle n° 123456 »).</li>
                        <li><b>Vérifiez l'aperçu</b> : Patria montre chaque ligne avant de l'enregistrer.</li></ol>""")),
                Components.card("Effacer des données", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list privacy-list">
                        <li>Supprimer une ligne, un bien ou un membre l'efface de la base.</li>
                        <li>Un <b>foyer supprimé</b> est mis de côté sur le disque, au cas où : l'administrateur l'efface définitivement dans
                        Administration → Foyers → Foyers supprimés.</li>
                        <li>Un <b>utilisateur supprimé</b> perd ses droits et sa progression dans le guide de saisie ; les données des foyers restent.</li>
                        <li>Avant chaque mise à jour de schéma, une copie de la base est faite à côté d'elle ; seules les <b>3 dernières</b> sont gardées.</li></ul>""")),
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
                        Définissez <code>DB_ENCRYPTION_KEY</code> pour les chiffrer.</li>"""))));
        return Layout.page("Confidentialité · Patria", Layout.Section.NONE, csrf, content);
    }
}
