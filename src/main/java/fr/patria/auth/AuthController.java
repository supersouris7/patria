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
        router.post("/deconnexion", this::logout);
    }

    private Response loginPage(Request request) {
        if (request.session() != null || sessions.find(request.cookie(Sessions.COOKIE)).isPresent()) return Response.redirect("/");
        Notice notice = request.query("bloque") != null ? Notice.error("Trop de tentatives. Réessayez dans quelques minutes.")
                : request.query("erreur") != null ? Notice.error("Identifiant ou mot de passe incorrect.")
                : request.query("sans-foyer") != null ? Notice.error("Aucun foyer n'est ouvert pour ce compte. Demandez à l'administrateur.")
                : request.query("deconnecte") != null ? Notice.success("Vous êtes déconnecté.") : null;
        return Response.page(page(notice, LocalPath.safe(request.query("suite"))));
    }

    private Response login(Request request) {
        String client = request.clientAddress();
        String next = LocalPath.safe(request.form().raw("suite"));
        String keep = next == null ? "" : "&suite=" + LocalPath.encode(next);
        if (throttle.isBlocked(client)) return Response.redirect("/connexion?bloque" + keep);
        String login = request.form().raw("login").trim();
        String entered = request.form().raw("password");
        Long userId;
        if (login.isEmpty() || login.equalsIgnoreCase(adminLogin)) {
            if (!password.matches(entered)) return failure(client, keep);
            userId = null;
        } else {
            Optional<Long> user = users.authenticate(login, entered);
            if (user.isEmpty()) return failure(client, keep);
            userId = user.get();
        }
        throttle.reset(client);
        OptionalLong household = users.firstHousehold(userId);
        if (household.isEmpty()) return Response.redirect("/connexion?sans-foyer");
        Session session = sessions.create(userId, household.getAsLong());
        String cookie = Sessions.COOKIE + "=" + session.id() + "; Path=/; Max-Age=" + Sessions.LIFETIME.toSeconds() + "; HttpOnly; SameSite=Lax"
                + (request.isHttps() ? "; Secure" : "");
        return new Response.Redirect(next == null || next.startsWith("/connexion") ? "/" : next, List.of(cookie));
    }

    private Response failure(String client, String keep) {
        throttle.recordFailure(client);
        return Response.redirect("/connexion?erreur" + keep);
    }

    private Response logout(Request request) {
        sessions.remove(request.session());
        return new Response.Redirect("/connexion?deconnecte", List.of(Sessions.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"));
    }

    static Html page(Notice notice, String next) {
        return Layout.document("Connexion · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1>Connexion</h1><p class="muted">Vos données restent sur votre serveur.</p>%s
                <form method="post" action="/connexion" class="stack">%s<label>Identifiant
                <input name="login" autocomplete="username" autocapitalize="none" spellcheck="false" required autofocus></label><label>Mot de passe
                <input name="password" type="password" autocomplete="current-password" required></label>
                <button type="submit">Ouvrir Patria</button></form></main>""", Layout.brand(), Components.notice(notice),
                next == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"suite\" value=\"%s\">", next)));
    }
}
