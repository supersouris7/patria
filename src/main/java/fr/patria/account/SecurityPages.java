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
                     String csrf) {
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
}
