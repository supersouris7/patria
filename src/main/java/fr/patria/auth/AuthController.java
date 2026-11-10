// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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

        /** Double authentification activee pour ce compte. */
        default boolean twoFactor(Long userId) {
            return false;
        }

        /** Code de l'application d'authentification juste (et pas deja utilise). */
        default boolean verifyCode(Long userId, String code) {
            return false;
        }

        /** Appareil reconnu ({@code token} : cookie de l'appareil) : le code n'est pas redemande. */
        default boolean trusted(Long userId, String token) {
            return false;
        }

        /** « Mot de passe oublie » propose (envoi d'e-mails regle par l'administrateur). */
        default boolean passwordReset() {
            return false;
        }

        /** Retient cet appareil 30 jours ; renvoie le jeton a mettre dans son cookie. */
        default String trustDevice(Long userId) {
            return null;
        }
    }

    private final Sessions sessions;
    private final String adminLogin;
    private final Password password;
    private final Users users;
    private final LoginThrottle throttle;
    private final Clock clock;

    public AuthController(Sessions sessions, String adminLogin, String password, Users users, Clock clock) {
        this.sessions = sessions;
        this.adminLogin = adminLogin;
        this.password = new Password(password);
        this.users = users;
        this.throttle = new LoginThrottle(clock);
        this.clock = clock;
    }

    /** Cookie d'un appareil reconnu par la double authentification. */
    public static final String DEVICE_COOKIE = "patria_appareil";
    static final int DEVICE_DAYS = 30;
    private static final int MAX_CODE_TRIES = 5;
    private static final java.time.Duration CODE_DELAY = java.time.Duration.ofMinutes(5);

    /** Mot de passe juste, code de double authentification attendu. */
    private record Pending(Long userId, String accountKey, String client, String next, long expiresAt, int tries) {
    }

    private final java.util.Map<String, Pending> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public void register(Router router) {
        router.publicGet("/connexion", this::loginPage);
        router.publicPost("/connexion", this::login);
        router.publicGet("/connexion/code", this::codePage);
        router.publicPost("/connexion/code", this::code);
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
                : request.query("sans-foyer") != null ? Notice.error("Aucun foyer n'est ouvert pour ce compte : demandez une invitation au gestionnaire de votre foyer.")
                : request.query("expire") != null ? Notice.error("Délai dépassé : reconnectez-vous.")
                : request.query("reinitialise") != null ? Notice.success("Mot de passe changé : connectez-vous avec le nouveau.")
                : request.query("deconnecte") != null ? Notice.success("Vous êtes déconnecté.") : null;
        return Response.page(page(notice, LocalPath.safe(request.query("suite")), fr.patria.web.Router.insecure(request), users.passwordReset()));
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
        OptionalLong household = users.firstHousehold(userId);
        if (household.isEmpty()) return Response.redirect("/connexion?sans-foyer");
        if (users.twoFactor(userId) && !users.trusted(userId, request.cookie(DEVICE_COOKIE))) {
            // Mot de passe juste : reste le code de l'application d'authentification.
            String token = token();
            long now = clock.millis();
            pending.values().removeIf(entry -> entry.expiresAt() < now);
            pending.put(token, new Pending(userId, accountKey, client, next, now + CODE_DELAY.toMillis(), 0));
            return Response.redirect("/connexion/code?jeton=" + token);
        }
        return open(request, userId, accountKey, client, next, false);
    }

    /** Connexion reussie : session, cookie, page suivante ({@code remember} : retenir l'appareil 30 jours). */
    private Response open(Request request, Long userId, String accountKey, String client, String next, boolean remember) {
        throttle.reset(accountKey);
        users.recorded(accountKey, userId, true, client);
        OptionalLong household = users.firstHousehold(userId);
        if (household.isEmpty()) return Response.redirect("/connexion?sans-foyer");
        Session session = sessions.create(userId, household.getAsLong());
        String secure = request.isHttps() ? "; Secure" : "";
        List<String> cookies = new java.util.ArrayList<>();
        cookies.add(Sessions.COOKIE + "=" + session.id() + "; Path=/; Max-Age=" + Sessions.LIFETIME.toSeconds() + "; HttpOnly; SameSite=Lax" + secure);
        if (remember) {
            String device = users.trustDevice(userId);
            if (device != null) cookies.add(DEVICE_COOKIE + "=" + device + "; Path=/; Max-Age=" + java.time.Duration.ofDays(DEVICE_DAYS).toSeconds() + "; HttpOnly; SameSite=Lax" + secure);
        }
        String target = next == null || next.startsWith("/connexion") ? "/" : next;
        // Premiere connexion : l'accueil, sauf si une page precise etait demandee (lien recu) ; il viendra a la suivante.
        if (next == null && !users.welcomed(userId)) target = "/bienvenue";
        if (users.mustChangePassword(userId)) target = "/compte/nouveau-mot-de-passe";
        return new Response.Redirect(target, cookies);
    }

    private Pending pending(String token) {
        Pending entry = token == null ? null : pending.get(token);
        if (entry == null) return null;
        if (entry.expiresAt() < clock.millis()) {
            pending.remove(token);
            return null;
        }
        return entry;
    }

    private Response codePage(Request request) {
        String token = request.query("jeton");
        if (pending(token) == null) return Response.redirect("/connexion?expire");
        Notice notice = request.query("erreur") != null ? Notice.error("Code incorrect. Vérifiez l'heure du téléphone, puis tapez le code affiché maintenant.") : null;
        return Response.page(codeForm(token, notice));
    }

    private Response code(Request request) {
        String token = request.form().raw("jeton");
        Pending entry = pending(token);
        if (entry == null) return Response.redirect("/connexion?expire");
        if (throttle.isBlocked(entry.client(), entry.accountKey())) {
            pending.remove(token);
            return Response.redirect("/connexion?bloque");
        }
        if (users.verifyCode(entry.userId(), request.form().raw("code"))) {
            pending.remove(token);
            return open(request, entry.userId(), entry.accountKey(), entry.client(), entry.next(), "on".equals(request.form().raw("retenir")));
        }
        throttle.recordFailure(entry.client(), entry.accountKey());
        users.recorded(entry.accountKey() + " (code 2FA)", null, false, entry.client());
        if (entry.tries() + 1 >= MAX_CODE_TRIES) {
            pending.remove(token);
            return Response.redirect("/connexion?erreur");
        }
        pending.put(token, new Pending(entry.userId(), entry.accountKey(), entry.client(), entry.next(), entry.expiresAt(), entry.tries() + 1));
        return Response.redirect("/connexion/code?jeton=" + token + "&erreur");
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Deuxieme etape de la connexion : le code a 6 chiffres de l'application d'authentification. */
    static Html codeForm(String token, Notice notice) {
        return Layout.document("Code de connexion · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1>Code de connexion</h1><p class="muted">Ouvrez votre application d'authentification et tapez le code
                à 6 chiffres affiché pour Patria.</p>%s
                <form method="post" action="/connexion/code" class="stack"><input type="hidden" name="jeton" value="%s"><label>Code
                <input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9 ]{6,7}" maxlength="7" required autofocus></label>
                <label class="check"><input type="checkbox" name="retenir" value="on">Se souvenir de cet appareil 30 jours</label>
                <button type="submit">Valider</button></form><p class="muted small"><a href="/connexion">Revenir à la connexion</a> · Téléphone perdu ?
                Demandez à l'administrateur de retirer votre double authentification.</p></main>""", Layout.brand(), Components.notice(notice), token));
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
        return page(notice, next, insecure, false);
    }

    static Html page(Notice notice, String next, boolean insecure, boolean reset) {
        return Layout.document("Connexion · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1 class="login-slogan">Connexion</h1>%s%s
                <form method="post" action="/connexion" class="stack">%s<label>Identifiant
                <input name="login" autocomplete="username" autocapitalize="none" spellcheck="false" required autofocus></label><label>Mot de passe
                <input name="password" type="password" autocomplete="current-password" required></label>
                <button type="submit">Ouvrir Patria</button></form><p class="muted login-links">%s<a href="/inscription">Créer un compte</a></p></main>""", Layout.brand(),
                insecure ? fr.patria.ui.SecurityPages.loginWarning() : Html.EMPTY, Components.notice(notice),
                next == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"suite\" value=\"%s\">", next),
                reset ? Html.raw("<a href=\"/connexion/oubli\">Mot de passe oublié ?</a> · ") : Html.EMPTY));
    }
}
