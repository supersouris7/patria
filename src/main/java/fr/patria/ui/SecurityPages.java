package fr.patria.ui;

/**
 * Alerte « connexion non chiffree (HTTP) » : fenetre rouge a l'ouverture de session, encadre sur la page de
 * connexion, et page d'aide pour passer en HTTPS (lisible sans connexion, et sans acces Internet).
 */
public final class SecurityPages {
    /** Section du README sur GitHub (documentation complete, exemples de configuration). */
    public static final String README_HTTPS = "https://github.com/supersouris7/patria/blob/main/docs/administration.md#passer-en-https";

    private SecurityPages() {
    }

    /** Ce que HTTP expose, en trois points (fenetre, page de connexion, page d'aide). */
    private static final Html CONSEQUENCES = Html.raw("""
            <ul class="risk-list"><li><b>Votre mot de passe circule en clair</b> : toute personne sur le même réseau (Wi-Fi public ou partagé,
            box, réseau d'entreprise) peut le lire.</li>
            <li><b>Votre session peut être volée</b> : avec le cookie de connexion, quelqu'un ouvre Patria à votre place pendant 12 heures, sans mot de passe.</li>
            <li><b>Vos données sont lisibles et modifiables en chemin</b> : revenus, dépenses, adresses des biens, prêts.</li></ul>""");

    /**
     * Fenetre rouge, par-dessus la page, tant que la connexion est en HTTP et que l'alerte n'a pas ete lue dans la
     * session. Sans JavaScript : un formulaire « J'ai compris » la ferme pour la session.
     */
    static Html overlay(String csrf) {
        Layout.Chrome chrome = Layout.CHROME.get();
        // Pas sur la page d'aide elle-meme : la fenetre la cacherait.
        if (chrome == null || !chrome.insecure() || (chrome.location() != null && chrome.location().startsWith("/aide/"))) return Html.EMPTY;
        return Html.of("""
                <div class="security-overlay"><section class="security-dialog" role="alertdialog" aria-modal="true" aria-labelledby="http-alert-title"\
                 aria-describedby="http-alert-text"><p class="eyebrow">Sécurité</p><h2 id="http-alert-title">Connexion non chiffrée (HTTP)</h2>
                <p id="http-alert-text">Patria est ouvert en <b>http://</b> : ce qui passe entre votre navigateur et le serveur n'est pas chiffré.</p>%s
                <p><b>Passez en HTTPS</b> : un reverse proxy (Caddy, Nginx Proxy Manager, Traefik) devant Patria obtient un certificat gratuit
                Let's Encrypt et chiffre tout. Comptez 10 minutes avec Caddy.</p>
                <div class="form-actions"><form method="post" action="/securite/http-compris">%s<input type="hidden" name="suite" value="%s">\
                <button class="danger" type="submit" autofocus>J'ai compris, continuer pour cette session</button></form>\
                <a class="button secondary" href="/aide/https">Comment passer en HTTPS</a></div>
                <p class="muted small">Cette alerte revient à chaque connexion tant que Patria est en HTTP. Elle n'apparaît pas quand Patria est ouvert
                depuis le serveur lui-même (localhost).</p></section></div>""",
                CONSEQUENCES, Components.csrfField(csrf), chrome.location() == null ? "/" : chrome.location());
    }

    /** Encadre rouge de la page de connexion, avant de taper le mot de passe. */
    public static Html loginWarning() {
        return Html.raw("""
                <section class="notice error http-warning" role="alert"><b>Connexion non chiffrée (HTTP)</b> : votre mot de passe va circuler en clair sur
                le réseau. <a href="/aide/https">Pourquoi et comment passer en HTTPS</a></section>""");
    }

    /** Page d'aide « Passer en HTTPS », ouverte a tous (liee depuis la page de connexion). */
    public static Html help(String csrf) {
        Html content = Html.concat(Components.heading("Aide", "Passer en HTTPS",
                        "Pourquoi Patria vous alerte quand il est ouvert en http://, et comment chiffrer la connexion en quelques minutes.", Html.EMPTY),
                Components.card("Ce que HTTP expose", null, Html.EMPTY, Html.concat(CONSEQUENCES, Html.raw("""
                        <p class="muted">Les navigateurs affichent « Non sécurisé » et les gestionnaires de mots de passe peuvent refuser de remplir le formulaire.
                        Ouvert depuis le serveur lui-même (<code>http://localhost</code>), rien ne passe par le réseau : pas d'alerte.</p>"""))),
                Components.card("La méthode conseillée : Caddy", null, Html.EMPTY, Html.raw("""
                        <ol class="steps"><li><b>Un nom de domaine</b> qui pointe vers votre serveur (le vôtre, ou un sous-domaine gratuit DuckDNS).
                        Ouvrez les ports 80 et 443 de votre box vers le serveur.</li>
                        <li><b>Ajoutez Caddy</b> à côté de Patria dans <code>compose.yaml</code> :
                        <pre><code>  caddy:
                            image: caddy:2
                            restart: unless-stopped
                            ports: ["80:80", "443:443"]
                            volumes:
                              - ./Caddyfile:/etc/caddy/Caddyfile:ro
                              - caddy-data:/data</code></pre>
                        et <code>caddy-data:</code> dans la liste <code>volumes:</code> du bas du fichier.</li>
                        <li><b>Créez le fichier <code>Caddyfile</code></b> (remplacez le nom de domaine) :
                        <pre><code>patria.example.fr {
                            reverse_proxy patria:8080
                        }</code></pre></li>
                        <li><b>Redémarrez</b> (<code>docker compose up -d</code>) et ouvrez <code>https://patria.example.fr</code> : Caddy obtient et renouvelle
                        seul le certificat Let's Encrypt. Gardez le port de Patria réservé au serveur lui-même (<code>127.0.0.1:…</code>, comme dans le
                        <code>compose.yaml</code> fourni) : on ne passe alors plus que par HTTPS.</li></ol>
                        <p class="muted">Caddy, Nginx Proxy Manager et Traefik transmettent l'en-tête <code>X-Forwarded-Proto: https</code> : Patria sait alors
                        que la connexion est chiffrée, marque son cookie « Secure » et l'alerte disparaît.</p>""")),
                Components.card("Autres solutions", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list"><li><b>Nginx Proxy Manager</b> : la même chose avec une interface web, pratique sur un NAS.</li>
                        <li><b>Traefik</b> : si vous l'utilisez déjà pour d'autres services Docker.</li>
                        <li><b>Accès seulement à distance, sans ouvrir de port</b> : Tailscale (<code>tailscale serve</code> fournit une adresse HTTPS
                        sur votre réseau privé).</li></ul>""")),
                Components.card("Documentation", null, Html.EMPTY, Html.of("""
                        <ul class="plain-list"><li><a href="%s" rel="noopener noreferrer">Patria : passer en HTTPS (README, avec les exemples)</a></li>
                        <li><a href="https://caddyserver.com/docs/quick-starts/reverse-proxy" rel="noopener noreferrer">Caddy : reverse proxy en une ligne</a></li>
                        <li><a href="https://letsencrypt.org/fr/getting-started/" rel="noopener noreferrer">Let's Encrypt : premiers pas</a></li>
                        <li><a href="https://nginxproxymanager.com/guide/" rel="noopener noreferrer">Nginx Proxy Manager : guide</a></li>
                        <li><a href="https://doc.traefik.io/traefik/https/acme/" rel="noopener noreferrer">Traefik : certificats Let's Encrypt</a></li>
                        <li><a href="https://tailscale.com/kb/1312/serve" rel="noopener noreferrer">Tailscale Serve</a></li></ul>""", README_HTTPS)));
        return Layout.page("Passer en HTTPS · Patria", Layout.Section.NONE, csrf, content);
    }

    /** Section du README sur le chiffrement des bases. */
    public static final String README_ENCRYPTION = "https://github.com/supersouris7/patria/blob/main/docs/administration.md#chiffrement-des-bases";

    /**
     * Page d'aide « Chiffrer les bases », pas a pas pour une installation Docker Compose. {@code encrypted} : deja
     * actif sur cette installation.
     */
    public static Html encryptionHelp(boolean encrypted, String csrf) {
        Html state = encrypted
                ? Html.raw("<p class=\"notice success\">Les bases de cette installation sont chiffrées. Conservez la clé d'origine ; ne générez pas une nouvelle clé. Testez vos sauvegardes et leur restauration.</p>")
                : Html.raw("<p class=\"notice warning\">Les bases de cette installation ne sont pas chiffrées : qui récupère le disque ou une sauvegarde peut les lire.</p>");
        Html content = Html.concat(Components.heading("Aide", "Chiffrer les bases",
                         "À configurer avant la première saisie. La clé protège les fichiers au repos ; HTTPS protège leur trajet sur le réseau.", Html.EMPTY),
                state,
                encrypted ? Html.EMPTY : Components.card("Pas à pas — installation encore en clair", null, Html.EMPTY, Html.raw("""
                        <ol class="steps help-steps">
                        <li><b>Faire une copie de sécurité</b>, au cas où :
                        <pre><code>docker compose stop
                        docker compose cp patria:/data ./sauvegarde-avant-chiffrement</code></pre></li>
                        <li><b>Générer une clé</b> (une longue suite de caractères au hasard) :
                        <pre><code>openssl rand -base64 32</code></pre>
                        Sans openssl : <code>head -c 32 /dev/urandom | base64</code>. Le résultat ressemble à <code>q3Zx8…Lw0=</code>.</li>
                        <li><b>La noter tout de suite en lieu sûr</b> : gestionnaire de mots de passe, ou papier rangé.
                        <span class="notice error inline-alert">Sans cette clé, les données sont perdues pour toujours : personne ne peut les récupérer.</span></li>
                        <li><b>L'ajouter au fichier <code>.env</code></b> : <code>nano .env</code>, une nouvelle ligne sans guillemets ni espace, puis
                        Ctrl+O, Entrée, Ctrl+X :
                        <pre><code>DB_ENCRYPTION_KEY=q3Zx8…Lw0=</code></pre></li>
                        <li><b>Redémarrer Patria</b> :
                        <pre><code>docker compose up -d --force-recreate
                        docker compose logs patria | grep -i chiffr</code></pre>
                        Les journaux affichent « Base chiffrée : … » pour chaque base.</li>
                        <li><b>Vérifier</b> : le menu Sécurité de l'administrateur indique « Bases chiffrées ».</li>
                        <li><b>Faire une nouvelle sauvegarde chiffrée et tester sa restauration</b>, avant de retirer la copie de l'étape 1, qui reste en clair.
                        Les anciennes sauvegardes ne sont pas converties automatiquement : chiffrez-les ou éliminez-les selon vos règles de conservation.
                        Ne supprimez pas votre seule copie vérifiée.</li></ol>""")),
                Components.card("Clé oubliée, absente ou perdue ?", null, Html.EMPTY, Html.raw("""
                        <ul class="plain-list"><li><b>Oubli au premier lancement</b> : les bases sont en clair ; vous pouvez activer le chiffrement ensuite.</li>
                        <li><b>Clé absente après activation</b> : remettez la clé d'origine dans la configuration et recréez le conteneur. N'en générez pas une autre.</li>
                        <li><b>Clé définitivement perdue</b> : les données chiffrées sont irrécupérables. Changer le mot de passe administrateur ne les déverrouille pas.</li>
                        <li>Les copies avant migration, sur le même disque, ne remplacent pas une sauvegarde hors serveur.</li></ul>""")),
                Components.card("À savoir", null, Html.EMPTY, Html.of("""
                        <ul class="plain-list">
                        <li><b>Ne changez jamais la clé</b> et ne la retirez pas de <code>.env</code> : Patria refuserait de démarrer (le message est dans
                        <code>docker compose logs patria</code>). Remettez la bonne clé et redémarrez.</li>
                        <li><b>Gardez la clé à part des sauvegardes</b> : les sauvegardes sont chiffrées avec elle ; l'une sans l'autre ne sert à rien
                        à un voleur, et les deux ensemble lui donnent tout.</li>
                        <li>Changer de clé ou revenir en clair n'est pas prévu pour l'instant.</li>
                        <li>Le chiffrement protège un disque, un volume ou une sauvegarde volés. Il ne protège pas contre la personne qui administre le
                        serveur : elle a la clé.</li>
                        <li><a href="%s" rel="noopener noreferrer">Guide administrateur : installation, sauvegarde et restauration</a></li></ul>""", README_ENCRYPTION)));
        return Layout.page("Chiffrer les bases · Patria", Layout.Section.NONE, csrf, content);
    }
}
