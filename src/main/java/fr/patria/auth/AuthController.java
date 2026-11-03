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

/** Connexion et deconnexion. */
public final class AuthController {
    private final Sessions sessions;
    private final Password password;
    private final LoginThrottle throttle;

    public AuthController(Sessions sessions, String password, Clock clock) {
        this.sessions = sessions;
        this.password = new Password(password);
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
                : request.query("erreur") != null ? Notice.error("Mot de passe incorrect.")
                : request.query("deconnecte") != null ? Notice.success("Vous êtes déconnecté.") : null;
        return Response.page(page(notice, LocalPath.safe(request.query("suite"))));
    }

    private Response login(Request request) {
        String client = request.clientAddress();
        String next = LocalPath.safe(request.form().raw("suite"));
        String keep = next == null ? "" : "&suite=" + LocalPath.encode(next);
        if (throttle.isBlocked(client)) return Response.redirect("/connexion?bloque" + keep);
        if (!password.matches(request.form().raw("password"))) {
            throttle.recordFailure(client);
            return Response.redirect("/connexion?erreur" + keep);
        }
        throttle.reset(client);
        Session session = sessions.create();
        String cookie = Sessions.COOKIE + "=" + session.id() + "; Path=/; Max-Age=" + Sessions.LIFETIME.toSeconds() + "; HttpOnly; SameSite=Lax"
                + (request.isHttps() ? "; Secure" : "");
        return new Response.Redirect(next == null || next.startsWith("/connexion") ? "/" : next, List.of(cookie));
    }

    private Response logout(Request request) {
        sessions.remove(request.session());
        return new Response.Redirect("/connexion?deconnecte", List.of(Sessions.COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"));
    }

    static Html page(Notice notice, String next) {
        return Layout.document("Connexion · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1>Connexion</h1><p class="muted">Vos données restent sur votre serveur.</p>%s
                <form method="post" action="/connexion" class="stack">%s<label>Mot de passe
                <input name="password" type="password" autocomplete="current-password" required autofocus></label>
                <button type="submit">Ouvrir Patria</button></form></main>""", Layout.brand(), Components.notice(notice),
                next == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"suite\" value=\"%s\">", next)));
    }
}
