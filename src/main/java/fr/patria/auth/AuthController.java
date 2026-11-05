package fr.patria.auth;

import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.web.LocalPath;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Session;
import fr.patria.web.Sessions;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Connexion et deconnexion. L'administrateur se connecte avec l'identifiant ADMIN_LOGIN (par defaut « admin », ou
 * identifiant laisse vide) et APP_PASSWORD ; les utilisateurs avec leur compte enregistre en base.
 */
public final class AuthController {
    /** Comptes enregistres en base, fournis par l'assemblage (module comptes). */
    public interface Users {
        /** Utilisateur actif dont l'identifiant et le mot de passe correspondent. */
        Optional<Long> authenticate(String login, String password);

        /** Premier foyer ouvert ; vide si aucun. {@code userId} nul pour l'administrateur. */
        OptionalLong firstHousehold(Long userId);

        /** Accueil de la premiere connexion deja vu. */
        default boolean welcomed(Long userId) {
            return true;
        }

        /** Mot de passe choisi par l'administrateur, a changer avant tout. */
        default boolean mustChangePassword(Long userId) {
            return false;
        }

        /** Journal des connexions : chaque essai, reussi ou non ({@code userId} nul : administrateur ou inconnu). */
        default void recorded(String login, Long userId, boolean success, String ip) {
        }
    }

    private final Sessions sessions;
    private final String adminLogin;
    private final Password password;
    private final Users users;
    private final LoginThrottle throttle;

    public AuthController(Sessions sessions, String adminLogin, String password, Users users, Clock clock) {
        this.sessions = sessions;
        this.adminLogin = adminLogin;
        this.password = new Password(password);
        this.users = users;
        this.throttle = new LoginThrottle(clock);
    }

    public void register(Router router) {
        router.publicGet("/connexion", this::loginPage);
        router.publicPost("/connexion", this::login);
        router.userPost("/deconnexion", this::logout);
        router.publicGet("/aide/https", request -> Response.page(fr.patria.ui.SecurityPages.help(request.csrf())));
        router.userPost("/securite/http-compris", request -> {
            sessions.acknowledgeHttp(request.session());
            String next = LocalPath.safe(request.form().raw("suite"));
            return Response.redirect(next == null ? "/" : next);
        });
    }

    private Response loginPage(Request request) {
        if (request.session() != null || sessions.find(request.cookie(Sessions.COOKIE)).isPresent()) return Response.redirect("/");
        Notice notice = request.query("bloque") != null ? Notice.error("Trop de tentatives. Réessayez dans quelques minutes.")
                : request.query("erreur") != null ? Notice.error("Identifiant ou mot de passe incorrect.")
                : request.query("sans-foyer") != null ? Notice.error("Aucun foyer n'est ouvert pour ce compte. Demandez à l'administrateur.")
                : request.query("deconnecte") != null ? Notice.success("Vous êtes déconnecté.") : null;
        return Response.page(page(notice, LocalPath.safe(request.query("suite")), fr.patria.web.Router.insecure(request)));
    }

    private Response login(Request request) {
        String client = request.clientAddress();
        String next = LocalPath.safe(request.form().raw("suite"));
        String keep = next == null ? "" : "&suite=" + LocalPath.encode(next);
        String identifier = request.form().raw("login").trim();
        String accountKey = identifier.isEmpty() ? adminLogin : identifier;
        if (throttle.isBlocked(client, accountKey)) {
            users.recorded(accountKey + " (bloqué)", null, false, client);
            return Response.redirect("/connexion?bloque" + keep);
        }
        String login = identifier;
        String entered = request.form().raw("password");
        Long userId;
        if (login.isEmpty() || login.equalsIgnoreCase(adminLogin)) {
            if (!password.matches(entered)) return failure(client, accountKey, keep);
            userId = null;
        } else {
            Optional<Long> user = users.authenticate(login, entered);
            if (user.isEmpty()) return failure(client, accountKey, keep);
            userId = user.get();
        }
        throttle.reset(accountKey);
        users.recorded(accountKey, userId, true, client);
        OptionalLong household = users.firstHousehold(userId);
        if (household.isEmpty()) return Response.redirect("/connexion?sans-foyer");
        Session session = sessions.create(userId, household.getAsLong());
        String cookie = Sessions.COOKIE + "=" + session.id() + "; Path=/; Max-Age=" + Sessions.LIFETIME.toSeconds() + "; HttpOnly; SameSite=Lax"
                + (request.isHttps() ? "; Secure" : "");
        String target = next == null || next.startsWith("/connexion") ? "/" : next;
        // Premiere connexion : l'accueil, sauf si une page precise etait demandee (lien recu) ; il viendra a la suivante.
        if (next == null && !users.welcomed(userId)) target = "/bienvenue";
        if (users.mustChangePassword(userId)) target = "/compte/nouveau-mot-de-passe";
        return new Response.Redirect(target, List.of(cookie));
    }

    /** Adresses et identifiants bloques en ce moment (page Securite). */
    public List<String> blocked() {
        return throttle.blocked();
    }

    private Response failure(String client, String login, String keep) {
        throttle.recordFailure(client, login);
        users.recorded(login, null, false, client);
        return Response.redirect("/connexion?erreur" + keep);
    }

    private Response logout(Request request) {
        sessions.remove(request.session());
        return new Response.Redirect("/connexion?deconnecte", List.of(Sessions.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"));
    }

    static Html page(Notice notice, String next) {
        return page(notice, next, false);
    }

    static Html page(Notice notice, String next, boolean insecure) {
        return Layout.document("Connexion · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1>Connexion</h1><p class="muted">Vos données restent sur votre serveur.</p>%s%s
                <form method="post" action="/connexion" class="stack">%s<label>Identifiant
                <input name="login" autocomplete="username" autocapitalize="none" spellcheck="false" required autofocus></label><label>Mot de passe
                <input name="password" type="password" autocomplete="current-password" required></label>
                <button type="submit">Ouvrir Patria</button></form><p class="muted"><a href="/confidentialite">Confidentialité</a></p></main>""", Layout.brand(),
                insecure ? fr.patria.ui.SecurityPages.loginWarning() : Html.EMPTY, Components.notice(notice),
                next == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"suite\" value=\"%s\">", next)));
    }
}
