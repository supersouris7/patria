package fr.patria.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import fr.patria.common.ForbiddenException;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Assets;
import fr.patria.ui.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aiguillage des requetes. Chaque module declare ses routes ({@code router.get("/bien/{id}", handler)}).
 *
 * <p>Regles communes appliquees ici, une fois pour toutes :
 * <ul>
 *   <li>route privee sans session -> redirection vers la page de connexion ;</li>
 *   <li>route reservee : {@link Access#ADMIN} par defaut ({@code get}, {@code post}), ouverte aux utilisateurs avec
 *       {@code userGet}/{@code userPost} (le controleur filtre alors selon {@link Viewer}), a ceux qui voient
 *       l'immobilier avec {@code estateGet} ; sinon 403 ;</li>
 *   <li>routes d'un foyer : le routeur principal confie la requete au routeur du foyer affiche par la session ;</li>
 *   <li>POST prive sans jeton CSRF valide -> 400 ;</li>
 *   <li>{@link ValidationException} -> 400, {@link NotFoundException} -> 404, autre exception -> 500 journalisee ;</li>
 *   <li>en-tetes de securite sur chaque page.</li>
 * </ul>
 */
public final class Router implements HttpHandler {
    @FunctionalInterface
    public interface Handler {
        Response handle(Request request);
    }

    /** Qui peut ouvrir une route privee. */
    public enum Access { ADMIN, USER, REAL_ESTATE }

    /** Droits de la personne connectee, relus a chaque requete ; vide si le compte n'existe plus ou n'a plus de foyer. */
    @FunctionalInterface
    public interface Viewers {
        Optional<Viewer> of(Session session);
    }

    /** Routeur des pages d'un foyer (chaque foyer a sa base). */
    @FunctionalInterface
    public interface Households {
        Router router(long householdId);
    }

    private record Route(String method, Pattern pattern, List<String> parameters, boolean isPrivate, Access access, Handler handler) {
    }

    private static final Logger LOG = Logger.getLogger(Router.class.getName());
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\w+)}");
    private static final String SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";
    private final List<Route> routes = new ArrayList<>();
    private final Sessions sessions;
    private final Viewers viewers;
    private final Households households;

    /** Routeur principal : connexion, comptes, puis routes du foyer affiche. */
    public Router(Sessions sessions, Viewers viewers, Households households) {
        this.sessions = sessions;
        this.viewers = viewers;
        this.households = households;
    }

    /** Routeur des pages d'un foyer, appele par le routeur principal. */
    public Router() {
        this(null, null, null);
    }

    // ---- Declaration des routes ----

    /** Page reservee a l'administrateur. */
    public void get(String path, Handler handler) {
        add("GET", path, true, Access.ADMIN, handler);
    }

    public void post(String path, Handler handler) {
        add("POST", path, true, Access.ADMIN, handler);
    }

    /** Page ouverte aux utilisateurs : le controleur ne montre que les vues ouvertes ({@link Request#viewer()}). */
    public void userGet(String path, Handler handler) {
        add("GET", path, true, Access.USER, handler);
    }

    public void userPost(String path, Handler handler) {
        add("POST", path, true, Access.USER, handler);
    }

    /** Consultation de l'immobilier, pour les utilisateurs qui y ont acces. */
    public void estateGet(String path, Handler handler) {
        add("GET", path, true, Access.REAL_ESTATE, handler);
    }

    public void publicGet(String path, Handler handler) {
        add("GET", path, false, Access.USER, handler);
    }

    public void publicPost(String path, Handler handler) {
        add("POST", path, false, Access.USER, handler);
    }

    private void add(String method, String path, boolean isPrivate, Access access, Handler handler) {
        List<String> parameters = new ArrayList<>();
        Matcher matcher = PARAMETER.matcher(path);
        StringBuilder regex = new StringBuilder();
        while (matcher.find()) {
            parameters.add(matcher.group(1));
            matcher.appendReplacement(regex, "(\\\\d{1,18})");
        }
        matcher.appendTail(regex);
        routes.add(new Route(method, Pattern.compile(regex.toString()), parameters, isPrivate, access, handler));
    }

    // ---- Traitement ----

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Session session = null;
        try {
            String path = exchange.getRequestURI().getPath();
            Assets.Asset asset = Assets.FILES.get(path);
            if (asset != null) {
                writeAsset(exchange, asset);
                return;
            }
            session = sessions.find(cookie(exchange)).orElse(null);
            Viewer viewer = session == null ? null : viewers.of(session).orElse(null);
            if (session != null && viewer == null) {
                // Compte desactive ou supprime, ou plus aucun foyer : la session est fermee.
                sessions.remove(session);
                session = null;
            }
            String query = exchange.getRequestURI().getRawQuery();
            Layout.CHROME.set(chrome(viewer, session != null && !session.httpAcknowledged() && insecure(exchange), path + (query == null ? "" : "?" + query)));
            write(exchange, dispatch(exchange, path, session, viewer));
        } catch (ValidationException exception) {
            write(exchange, new Response.Page(400, Layout.message("Demande invalide", exception.getMessage(), csrf(session))));
        } catch (ForbiddenException exception) {
            write(exchange, new Response.Page(403, Layout.message("Accès réservé", exception.getMessage(), csrf(session))));
        } catch (NotFoundException exception) {
            write(exchange, new Response.Page(404, Layout.message(exception.title(), exception.getMessage(), csrf(session))));
        } catch (PayloadTooLargeException exception) {
            write(exchange, new Response.Page(413, Layout.message("Demande trop volumineuse", "Le formulaire ou le fichier dépasse la taille autorisée.", csrf(session))));
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "Erreur sur " + exchange.getRequestMethod() + " " + exchange.getRequestURI(), exception);
            write(exchange, new Response.Page(500, Layout.message("Erreur interne", "Une erreur est survenue.", csrf(session))));
        } finally {
            Layout.CHROME.remove();
            exchange.close();
        }
    }

    /** Ce que l'en-tete affiche : nom, foyer et rubriques permises. */
    private static Layout.Chrome chrome(Viewer viewer, boolean insecure, String location) {
        if (viewer == null) return null;
        return new Layout.Chrome(new Layout.Chrome.Person(viewer.name(), viewer.isAdministrator(), viewer.realEstate(), viewer.realEstate()),
                viewer.households().stream().map(household -> new Layout.Choice(household.id(), household.name(), household.managed())).toList(), viewer.household().id(),
                new Layout.Chrome.Alerts(insecure, viewer.audience().lines(), viewer.audience().changes(), viewer.notice()),
                location);
    }

    /**
     * Connexion non chiffree : ni HTTPS (en-tete X-Forwarded-Proto du reverse proxy), ni ouverte depuis le serveur
     * lui-meme (localhost), ou rien ne circule sur le reseau.
     */
    public static boolean insecure(Request request) {
        return insecure(request.exchange());
    }

    static boolean insecure(HttpExchange exchange) {
        if ("https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"))) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        String name = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT).replaceFirst(":\\d+$", "");
        return !(name.equals("localhost") || name.startsWith("127.") || name.equals("[::1]"));
    }

    /** Pages ouvertes tant que le mot de passe choisi par l'administrateur n'est pas change. */
    private static final java.util.Set<String> BEFORE_PASSWORD_CHANGE = java.util.Set.of("/compte/nouveau-mot-de-passe", "/compte/mot-de-passe", "/deconnexion",
            "/aide/https", "/aide/chiffrement", "/securite/http-compris", "/confidentialite", "/connexion", "/sante");

    private Response dispatch(HttpExchange exchange, String path, Session session, Viewer viewer) {
        String method = exchange.getRequestMethod().equals("HEAD") ? "GET" : exchange.getRequestMethod();
        if (households != null && viewer != null && viewer.mustChangePassword() && !BEFORE_PASSWORD_CHANGE.contains(path)) {
            return Response.redirect("/compte/nouveau-mot-de-passe");
        }
        boolean pathExists = false;
        for (Route route : routes) {
            Matcher matcher = route.pattern().matcher(path);
            if (!matcher.matches()) continue;
            pathExists = true;
            if (!route.method().equals(method)) continue;
            if (route.isPrivate() && session == null) {
                String query = exchange.getRequestURI().getRawQuery();
                String target = path + (query == null ? "" : "?" + query);
                return Response.redirect(method.equals("GET") && !target.equals("/") ? "/connexion?suite=" + LocalPath.encode(target) : "/connexion");
            }
            Map<String, String> parameters = new HashMap<>();
            for (int i = 0; i < route.parameters().size(); i++) parameters.put(route.parameters().get(i), matcher.group(i + 1));
            if (route.isPrivate() && !allowed(route.access(), viewer, households != null)) {
                throw new ForbiddenException(route.access() == Access.REAL_ESTATE ? "L'immobilier n'est pas ouvert pour votre compte."
                        : households != null ? "Cette page est réservée à l'administrateur." : "Cette page demande l'accès Immobilier : demandez-le à l'administrateur.");
            }
            Request request = new Request(exchange, parameters, session, viewer);
            if (route.isPrivate() && method.equals("POST")) checkCsrf(request);
            return route.handler().handle(request);
        }
        if (!pathExists && households != null) {
            if (viewer != null && viewer.isAdministrator()) {
                // L'administrateur ne voit aucune donnee des foyers : son accueil est l'administration des comptes.
                if (method.equals("GET") && (path.equals("/") || path.equals("/administration"))) return Response.redirect("/administration/foyers");
                throw new ForbiddenException("L'administrateur gère les comptes, les foyers et les droits, mais ne voit pas les données des foyers. "
                        + "Pour votre propre budget, créez-vous un compte utilisateur.");
            }
            if (viewer != null) return households.router(viewer.household().id()).dispatch(exchange, path, session, viewer);
            String query = exchange.getRequestURI().getRawQuery();
            String target = path + (query == null ? "" : "?" + query);
            return Response.redirect(method.equals("GET") && !target.equals("/") ? "/connexion?suite=" + LocalPath.encode(target) : "/connexion");
        }
        if (pathExists) return new Response.Page(405, Layout.message("Méthode non autorisée", "Cette action n'est pas disponible.", csrf(session)));
        throw new NotFoundException("Page introuvable", "Cette page n'existe pas.");
    }

    /**
     * {@link Access#ADMIN} : sur le routeur principal (foyers, utilisateurs), l'administrateur de l'application ; sur
     * le routeur d'un foyer, les pages de gestion de l'immobilier (case « Immobilier » du tableau des droits).
     */
    private static boolean allowed(Access access, Viewer viewer, boolean main) {
        return switch (access) {
            case ADMIN -> main ? viewer.isAdministrator() : viewer.realEstate();
            case USER -> true;
            case REAL_ESTATE -> viewer.realEstate();
        };
    }

    private static void checkCsrf(Request request) {
        byte[] expected = request.csrf().getBytes(StandardCharsets.UTF_8);
        byte[] received = request.form().raw("csrf").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, received)) throw new ValidationException("Formulaire expiré. Rechargez la page.");
    }

    // ---- Ecriture ----

    private static void write(HttpExchange exchange, Response response) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "same-origin");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        // HTTPS (derriere le proxy) : le navigateur n'essaiera plus le HTTP pendant un an.
        if ("https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"))) {
            headers.set("Strict-Transport-Security", "max-age=31536000");
        }
        switch (response) {
            case Response.Page page -> {
                headers.set("Content-Type", "text/html; charset=utf-8");
                headers.set("Content-Security-Policy", SECURITY_POLICY);
                send(exchange, page.status(), page.html().toString().getBytes(StandardCharsets.UTF_8));
            }
            case Response.Text text -> {
                headers.set("Content-Type", "text/plain; charset=utf-8");
                send(exchange, text.status(), text.body().getBytes(StandardCharsets.UTF_8));
            }
            case Response.Redirect redirect -> {
                redirect.cookies().forEach(cookie -> headers.add("Set-Cookie", cookie));
                headers.set("Location", redirect.location());
                exchange.sendResponseHeaders(303, -1);
            }
            case Response.NoContent ignored -> exchange.sendResponseHeaders(204, -1);
            case Response.Download download -> {
                headers.set("Content-Type", download.contentType());
                headers.set("Content-Disposition", "attachment; filename=\"" + download.filename().replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
                headers.set("Cache-Control", "no-store");
                send(exchange, 200, download.body());
            }
        }
    }

    private static void writeAsset(HttpExchange exchange, Assets.Asset asset) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", asset.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        send(exchange, 200, asset.content());
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) exchange.getResponseBody().write(body);
    }

    private static String cookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] entry = part.trim().split("=", 2);
            if (entry.length == 2 && entry[0].equals(Sessions.COOKIE)) return entry[1];
        }
        return null;
    }

    private static String csrf(Session session) {
        return session == null ? null : session.csrf();
    }

    /** Page HTML d'erreur pour les controleurs qui veulent la renvoyer directement. */
    public static Response error(int status, String title, String text, String csrf) {
        return new Response.Page(status, Layout.message(title, text, csrf));
    }

}
