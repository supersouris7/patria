package fr.patria.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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

    private record Route(String method, Pattern pattern, List<String> parameters, boolean isPrivate, Handler handler) {
    }

    private static final Logger LOG = Logger.getLogger(Router.class.getName());
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\w+)}");
    private static final String SECURITY_POLICY = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";
    private final List<Route> routes = new ArrayList<>();
    private final Sessions sessions;

    public Router(Sessions sessions) {
        this.sessions = sessions;
    }

    // ---- Declaration des routes ----

    public void get(String path, Handler handler) {
        add("GET", path, true, handler);
    }

    public void post(String path, Handler handler) {
        add("POST", path, true, handler);
    }

    public void publicGet(String path, Handler handler) {
        add("GET", path, false, handler);
    }

    public void publicPost(String path, Handler handler) {
        add("POST", path, false, handler);
    }

    private void add(String method, String path, boolean isPrivate, Handler handler) {
        List<String> parameters = new ArrayList<>();
        Matcher matcher = PARAMETER.matcher(path);
        StringBuilder regex = new StringBuilder();
        while (matcher.find()) {
            parameters.add(matcher.group(1));
            matcher.appendReplacement(regex, "(\\\\d{1,18})");
        }
        matcher.appendTail(regex);
        routes.add(new Route(method, Pattern.compile(regex.toString()), parameters, isPrivate, handler));
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
            write(exchange, dispatch(exchange, path, session));
        } catch (ValidationException exception) {
            write(exchange, new Response.Page(400, Layout.message("Demande invalide", exception.getMessage(), csrf(session))));
        } catch (NotFoundException exception) {
            write(exchange, new Response.Page(404, Layout.message(exception.title(), exception.getMessage(), csrf(session))));
        } catch (PayloadTooLargeException exception) {
            write(exchange, new Response.Page(413, Layout.message("Demande trop volumineuse", "Le formulaire ou le fichier dépasse la taille autorisée.", csrf(session))));
        } catch (RuntimeException exception) {
            LOG.log(Level.SEVERE, "Erreur sur " + exchange.getRequestMethod() + " " + exchange.getRequestURI(), exception);
            write(exchange, new Response.Page(500, Layout.message("Erreur interne", "Une erreur est survenue.", csrf(session))));
        } finally {
            exchange.close();
        }
    }

    private Response dispatch(HttpExchange exchange, String path, Session session) {
        String method = exchange.getRequestMethod().equals("HEAD") ? "GET" : exchange.getRequestMethod();
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
            Request request = new Request(exchange, parameters, session);
            if (route.isPrivate() && method.equals("POST")) checkCsrf(request);
            return route.handler().handle(request);
        }
        if (pathExists) return new Response.Page(405, Layout.message("Méthode non autorisée", "Cette action n'est pas disponible.", csrf(session)));
        throw new NotFoundException("Page introuvable", "Cette page n'existe pas.");
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
