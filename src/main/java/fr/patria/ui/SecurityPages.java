package fr.patria.ui;

/**
 * Alerte « connexion non chiffree (HTTP) » : fenetre rouge a l'ouverture de session, encadre sur la page de
 * connexion, et page d'aide pour passer en HTTPS (lisible sans connexion, et sans acces Internet).
 */
public final class SecurityPages {
    /** Section du README sur GitHub (documentation complete, exemples de configuration). */
    public static final String README_HTTPS = "https://github.com/supersouris7/patria#passer-en-https";

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
}
