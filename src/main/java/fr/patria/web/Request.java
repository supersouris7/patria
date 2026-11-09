package fr.patria.web;

import com.sun.net.httpserver.HttpExchange;
import fr.patria.ui.Notice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.Map;

/** Requete HTTP vue par un controleur : parametres de chemin, formulaire, session. */
public final class Request {
    private static final int MAX_FORM_BYTES = 64 * 1024;
    /** Fichier envoye (CSV, ou document PDF pour l'IA locale). */
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private final HttpExchange exchange;
    private final Map<String, String> pathParameters;
    private final Session session;
    private final Viewer viewer;
    private FormData form;

    Request(HttpExchange exchange, Map<String, String> pathParameters, Session session, Viewer viewer) {
        this.exchange = exchange;
        this.pathParameters = pathParameters;
        this.session = session;
        this.viewer = viewer;
    }

    /** Droits de la personne connectee ; toujours present sur une route privee. */
    public Viewer viewer() {
        return viewer;
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public String path() {
        return exchange.getRequestURI().getPath();
    }

    /** Identifiant numerique du chemin, par exemple {@code id} dans {@code /bien/{id}}. */
    public long id(String name) {
        return Long.parseLong(pathParameters.get(name));
    }

    public long id() {
        return id("id");
    }

    /** Parametre d'URL decode comme un formulaire GET ({@code +} = espace, {@code %C3%A9} = é) ; null si absent ou illisible. */
    public String query(String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        try {
            for (String part : query.split("&")) {
                String[] entry = part.split("=", 2);
                if (java.net.URLDecoder.decode(entry[0], java.nio.charset.StandardCharsets.UTF_8).equals(key)) {
                    return entry.length == 2 ? java.net.URLDecoder.decode(entry[1], java.nio.charset.StandardCharsets.UTF_8) : "";
                }
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return null;
    }

    /** Message de confirmation transmis par {@code ?ok=code} apres une redirection. */
    public Notice confirmation() {
        return Notice.confirmation(query("ok"));
    }

    /** Session de la personne connectee ; toujours presente sur une route privee. */
    public Session session() {
        return session;
    }

    public String csrf() {
        return session == null ? null : session.csrf();
    }

    public String header(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    public String cookie(String name) {
        String header = header("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] entry = part.trim().split("=", 2);
            if (entry.length == 2 && entry[0].equals(name)) return entry[1];
        }
        return null;
    }

    /** Adresse du client. Derriere le reverse proxy local, la derniere entree de X-Forwarded-For (ajoutee par le proxy). */
    public String clientAddress() {
        String forwarded = header("X-Forwarded-For");
        if (fromProxy(exchange) && forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /** Connexion venue du reverse proxy (machine locale ou reseau prive) : seuls ses en-tetes X-Forwarded-* sont crus. */
    static boolean fromProxy(HttpExchange exchange) {
        InetAddress remote = exchange.getRemoteAddress().getAddress();
        return remote.isLoopbackAddress() || remote.isSiteLocalAddress();
    }

    /** Page servie en HTTPS par le reverse proxy ; un client direct ne peut pas le pretendre. */
    static boolean https(HttpExchange exchange) {
        return fromProxy(exchange) && "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
    }

    HttpExchange exchange() {
        return exchange;
    }

    public boolean isHttps() {
        return https(exchange);
    }

    /** Formulaire envoye (urlencoded ou multipart), lu une seule fois. */
    public FormData form() {
        if (form == null) {
            String type = header("Content-Type");
            form = type != null && type.startsWith("multipart/form-data")
                    ? MultipartParser.parse(type, body(MAX_UPLOAD_BYTES))
                    : FormData.urlEncoded(body(MAX_FORM_BYTES));
        }
        return form;
    }

    private byte[] body(int maximum) {
        String length = header("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > maximum) throw new PayloadTooLargeException();
            } catch (NumberFormatException exception) {
                throw new PayloadTooLargeException();
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream input = exchange.getRequestBody()) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                if (output.size() + read > maximum) throw new PayloadTooLargeException();
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }
}
