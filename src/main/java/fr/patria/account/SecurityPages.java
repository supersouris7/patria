package fr.patria.account;

import fr.patria.ui.BuildInfo;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Page Securite de l'administrateur. HTML uniquement. */
final class SecurityPages {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Etat de l'installation : HTTPS pour cette connexion ({@code local} : ouverte depuis le serveur lui-meme), bases
     * chiffrees, mode hors ligne, copies de sauvegarde.
     */
    record State(boolean https, boolean local, boolean encrypted, boolean offline, int backups, boolean exposed, boolean adminTwoFactor) {
    }

    private SecurityPages() {
    }

    static Html page(State state, int failures24h, int failures7d, List<String> blocked, List<SecurityLog.LoginEvent> failures,
                     List<SecurityLog.LoginEvent> successes, Map<String, String> places, List<User> users, Map<Long, LocalDateTime> lastLogins,
                     Map<Long, Integer> open, java.util.Set<Long> provisional, Map<String, java.time.LocalDate> twoFactor, List<SecurityLog.AuditEntry> audit,
                     AccountRepository.Mail mail, String mailStatus, String csrf) {
        Html tiles = Components.metrics(4,
                Components.tile("Échecs sur 24 h", null, failures24h, "mots de passe faux ou comptes inconnus"),
                Components.tile("Échecs sur 7 jours", null, failures7d, null),
                Components.tile("Bloqués en ce moment", null, blocked.size(), "adresses ou identifiants"),
                Components.tile("Sessions ouvertes", null, open.values().stream().mapToInt(Integer::intValue).sum(), "tous comptes confondus"));
        Html recommended = state.adminTwoFactor() || !state.exposed() ? Html.EMPTY : Html.raw("""
                <p class="notice error"><b>Double authentification fortement recommandée</b> : Patria est accessible depuis Internet et le compte
                administrateur n'est protégé que par son mot de passe. <a href="/compte/double-authentification">L'activer maintenant</a></p>""");
        Html installation = Html.of("<ul class=\"check-list\">%s%s%s%s%s%s</ul>",
                check(state.adminTwoFactor(), state.adminTwoFactor() ? "Double authentification de l'administrateur activée."
                                : state.exposed() ? "Double authentification de l'administrateur : fortement recommandée (Patria est accessible depuis Internet)."
                                : "Double authentification de l'administrateur non activée.", state.adminTwoFactor() ? null : "/compte/double-authentification"),
                check(state.https() || state.local(), state.https() ? "Connexion chiffrée (HTTPS)."
                                : state.local() ? "Connexion locale (localhost) : rien ne passe par le réseau. Vérifiez que l'adresse utilisée depuis l'extérieur est en HTTPS."
                                : "Connexion en HTTP : mots de passe et données circulent en clair.", state.https() || state.local() ? null : "/aide/https"),
                check(state.encrypted(), state.encrypted() ? "Bases chiffrées (DB_ENCRYPTION_KEY)." : "Bases non chiffrées : définissez DB_ENCRYPTION_KEY.",
                        state.encrypted() ? null : "/aide/chiffrement"),
                check(true, state.offline() ? "Mode hors ligne : aucun appel extérieur." : "Appels extérieurs : prix du marché, taux, localisation des adresses IP.", null),
                check(state.backups() > 0, state.backups() + " copie" + (state.backups() > 1 ? "s" : "") + " « avant-migration » à côté des bases (les 3 dernières par base).", null),
                check(true, "Version " + BuildInfo.version() + " · commit " + BuildInfo.shortCommit() + ".", null));
        installation = Html.concat(installation, Html.raw("<p class=\"muted small\">Les copies avant migration restent sur le même disque : elles ne remplacent pas une sauvegarde hors serveur ni un test de restauration. Conservez la clé séparément.</p>"));
        Html blockedList = blocked.isEmpty() ? Html.raw("<p class=\"empty\">Aucun blocage en cours.</p>")
                : Html.of("<ul class=\"plain-list\">%s</ul><p class=\"muted small\">Blocage de 15 minutes après 10 échecs depuis une adresse, ou 30 sur un même identifiant.</p>",
                blocked.stream().map(item -> Html.of("<li>%s</li>", item)).collect(Html.joining()));
        Html failureRows = failures.stream().map(event -> Html.of("<tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>",
                event.at().format(WHEN), event.login().isEmpty() ? "(vide)" : event.login(), event.ip(), places.getOrDefault(event.ip(), ""))).collect(Html.joining());
        Html successRows = successes.stream().map(event -> Html.of("<tr><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>",
                event.at().format(WHEN), account(event, users), event.ip(), places.getOrDefault(event.ip(), ""))).collect(Html.joining());
        Html accountRows = Html.concat(accountRow("Administrateur", lastLogins.get(0L), open.getOrDefault(0L, 0), "défini dans la configuration",
                        twoFactor.get(TwoFactorStore.account(null))),
                users.stream().map(user -> accountRow(user.name() + (user.active() ? "" : " (désactivé)"), lastLogins.get(user.id()), open.getOrDefault(user.id(), 0),
                        provisional.contains(user.id()) ? "provisoire, à changer à la connexion" : "choisi par l'utilisateur", twoFactor.get(TwoFactorStore.account(user.id()))))
                        .collect(Html.joining()));
        Html auditRows = audit.stream().map(entry -> Html.of("<tr><td>%s</td><td>%s</td><td>%s</td></tr>", entry.at().format(WHEN), entry.actor(), entry.detail()))
                .collect(Html.joining());
        Html place = Html.raw(state.offline() ? "<p class=\"muted small\">Mode hors ligne : pas de localisation des adresses IP.</p>"
                : "<p class=\"muted small\">Pays et ville : demandés à ipwho.is (seule l'adresse IP est envoyée, une fois par adresse), approximatifs.</p>");
        Html content = Html.concat(Components.heading("Administration", "Sécurité",
                        "Connexions, blocages, journal des droits et état de l'installation. Aucune donnée des foyers ici. Journal des connexions gardé "
                                + SecurityLog.KEPT_DAYS + " jours.", Html.EMPTY),
                recommended, tiles,
                Components.card("État de l'installation", null, Html.EMPTY, installation),
                localAiCard(csrf), mailCard(mail, mailStatus, csrf),
                Components.card("Bloqués en ce moment", blocked.size(), Html.EMPTY, blockedList),
                Components.disclosure("Connexions échouées (" + failures.size() + ")", failures.isEmpty() ? Html.raw("<p class=\"empty\">Aucun échec enregistré.</p>")
                        : Html.concat(Components.table(List.of("Date", "Identifiant tenté", "Adresse IP", "Lieu"), failureRows, false, false), place), !failures.isEmpty()),
                Components.card("Comptes", users.size() + 1, Html.EMPTY,
                        Components.table(List.of("Compte", "Dernière connexion", "#Sessions ouvertes", "Mot de passe", "Double authentification"), accountRows, false, false)),
                Components.disclosure("Connexions réussies (" + successes.size() + ")", successes.isEmpty() ? Html.raw("<p class=\"empty\">Aucune connexion enregistrée.</p>")
                        : Html.concat(Components.table(List.of("Date", "Compte", "Adresse IP", "Lieu"), successRows, false, false), place), false),
                Components.disclosure("Journal des droits (" + audit.size() + ")", audit.isEmpty() ? Html.raw("<p class=\"empty\">Aucun changement enregistré.</p>")
                        : Html.concat(Html.raw("<p class=\"muted\">Comptes créés, accès ouverts ou retirés, mots de passe réinitialisés, foyers créés ou supprimés.</p>"),
                        Components.table(List.of("Date", "Par", "Changement"), auditRows, false, false)), false));
        return Layout.adminSection("Sécurité · Patria", csrf, Layout.AdminSection.SECURITY, content);
    }

    private static Html check(boolean ok, String text, String link) {
        return Html.of("<li class=\"%s\"><span aria-hidden=\"true\">%s</span> %s%s</li>", ok ? "ok" : "ko", ok ? "✓" : "!", text,
                link == null ? Html.EMPTY : Html.of(" <a href=\"%s\">%s</a>", link, link.endsWith("chiffrement") ? "Comment faire"
                        : link.endsWith("double-authentification") ? "Activer" : "Passer en HTTPS"));
    }

    private static String account(SecurityLog.LoginEvent event, List<User> users) {
        if (event.userId() == null) return "Administrateur";
        return users.stream().filter(user -> user.id() == event.userId()).map(User::name).findFirst().orElse(event.login() + " (supprimé)");
    }

    private static Html accountRow(String name, LocalDateTime last, int open, String password, java.time.LocalDate twoFactor) {
        Html state = twoFactor == null ? Html.raw("<span class=\"badge\">Non</span>")
                : Html.of("<span class=\"badge paid\">Activée%s</span>", twoFactor.equals(java.time.LocalDate.MIN) ? ""
                : " le " + twoFactor.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        return Html.of("<tr><td>%s</td><td>%s</td><td class=\"num\">%s</td><td>%s</td><td>%s</td></tr>", name, last == null ? "Jamais" : last.format(WHEN), open, password, state);
    }

    /**
     * Envoi d'e-mails (facultatif) : serveur SMTP, adresse publique pour les liens, test, puis confirmation exigee des
     * nouveaux comptes. Sans reglage, l'e-mail reste facultatif et rien n'est envoye.
     */
    private static Html mailCard(AccountRepository.Mail mail, String status, String csrf) {
        fr.patria.common.Smtp.Settings smtp = mail.smtp();
        Html securities = java.util.Arrays.stream(fr.patria.common.Smtp.Security.values()).map(security -> Html.of("<option value=\"%s\"%s>%s</option>", security.name(),
                Html.raw(security == smtp.security() ? " selected" : ""), switch (security) {
                    case STARTTLS -> "STARTTLS (port 587)";
                    case TLS -> "TLS (port 465)";
                    case NONE -> "Aucun (réseau local seulement)";
                })).collect(Html.joining());
        return Html.of("<div id=\"email\">%s</div>", Components.card("E-mail", null, Html.EMPTY, Html.of("""
                <p>Facultatif. Avec un serveur d'envoi (celui de votre fournisseur d'e-mail, Brevo, Gmail avec un mot de passe d'application…), Patria peut
                confirmer l'adresse des nouveaux comptes et envoyer un lien en cas de <b>mot de passe oublié</b>. Sans réglage, l'e-mail des comptes reste
                facultatif et rien n'est envoyé.</p>%s
                <form method="post" action="/administration/securite/email" class="stack">%s
                <div class="form-grid cols-3"><label>Serveur SMTP<input name="host" value="%s" maxlength="200" placeholder="smtp.example.fr"></label>
                <label>Port<input name="port" value="%s" inputmode="numeric" maxlength="5"></label>
                <label>Chiffrement<select name="securite">%s</select></label>
                <label>Utilisateur<input name="utilisateur" value="%s" maxlength="200" autocomplete="off"></label>
                <label>Mot de passe<input name="mot_de_passe" type="password" maxlength="200" autocomplete="new-password" placeholder="%s"></label>
                <label>Expéditeur<input name="expediteur" value="%s" maxlength="254" placeholder="patria@example.fr"></label></div>
                <label>Adresse publique de Patria (pour les liens des e-mails)<input name="adresse" value="%s" maxlength="200" placeholder="https://patria.example.fr"></label>
                <label class="check"><input type="checkbox" name="exiger" value="on"%s> Exiger un e-mail confirmé pour les nouveaux comptes (demandes d'inscription et
                comptes créés ici)</label>
                <div class="form-actions"><button class="secondary" type="submit">Enregistrer</button></div></form>
                <form method="post" action="/administration/securite/email/test" class="row-form">%s<label>Envoyer un e-mail de test à
                <input name="a" type="email" maxlength="254" required></label><button class="secondary" type="submit">Tester</button></form>""",
                status == null ? Html.EMPTY : Html.of("<p class=\"notice %s\">%s</p>", status.startsWith("E-mail de test envoyé") ? "success" : "warning", status),
                Components.csrfField(csrf), smtp.host(), smtp.port(), securities, smtp.user(), smtp.password().isEmpty() ? "" : "inchangé si vide", smtp.from(),
                mail.publicUrl(), Html.raw(mail.required() ? " checked" : ""), Components.csrfField(csrf))));
    }

    /** IA locale : oui ou non, adresse du serveur Ollama et modele, avec le resultat du dernier test. */
    private static Html localAiCard(String csrf) {
        fr.patria.ai.LocalAi.Settings settings = fr.patria.ai.LocalAi.settings();
        String status = fr.patria.ai.LocalAi.status();
        return Html.of("<div id=\"ia-locale\">%s</div>", Components.card("IA locale", null, Html.EMPTY, Html.of("""
                <p>Sans IA locale, les imports proposent un <b>prompt à copier</b> dans un assistant externe (avec un avertissement sur les données). Avec une IA
                locale (<a href="https://ollama.com" target="_blank" rel="noopener noreferrer">Ollama</a> installé à côté de Patria), l'utilisateur dépose simplement
                son document : Patria le fait analyser sur votre serveur, <b>rien ne sort</b>. Il faut environ 4 Go de mémoire libre pour un modèle 3B, 8 Go pour un 7B.</p>
                %s
                <form method="post" action="/administration/securite/ia" class="stack">%s
                <label class="check"><input type="checkbox" name="ia" value="on"%s> Utiliser une IA locale pour les imports</label>
                <div class="row-form"><label>Adresse du serveur Ollama<input name="url" value="%s" maxlength="200"></label>
                <label>Modèle<input name="modele" value="%s" maxlength="80" list="ia-modeles"></label><datalist id="ia-modeles">%s</datalist>
                <button class="secondary" type="submit">Enregistrer et tester</button></div></form>""",
                status == null ? Html.EMPTY : Html.of("<p class=\"notice %s\">%s</p>", status.startsWith("IA locale prête") ? "success" : "warning", status),
                Components.csrfField(csrf), Html.raw(settings.enabled() ? " checked" : ""), settings.url(), settings.model(),
                fr.patria.ai.LocalAi.SUGGESTED.stream().map(model -> Html.of("<option value=\"%s\">", model)).collect(Html.joining()))));
    }
}
