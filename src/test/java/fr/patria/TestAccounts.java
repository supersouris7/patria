package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comptes pour les tests HTTP : l'administrateur ne voit pas les finances, les donnees se saisissent avec un compte
 * gestionnaire. {@link #account} cree le compte, lui ouvre ses droits et change son mot de passe provisoire.
 */
final class TestAccounts {
    /** Une personne connectee : son cookie de session et son jeton CSRF. */
    record Visitor(String cookie, String csrf, long id) {
    }

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");

    private TestAccounts() {
    }

    static HttpResponse<String> get(HttpClient client, String base, Visitor visitor, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient client, String base, Visitor visitor, String path, String form) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        String body = visitor == null ? form : "csrf=" + visitor.csrf() + (form.isEmpty() ? "" : "&" + form);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Connexion ; un mot de passe provisoire est remplace par {@code password + "-final"}... sauf si {@code keep}. */
    static Visitor login(HttpClient client, String base, String login, String password) throws Exception {
        HttpResponse<String> logged = post(client, base, null, "/connexion", "login=" + login + "&password=" + password);
        assertEquals(303, logged.statusCode(), "connexion de " + login);
        String cookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        String location = logged.headers().firstValue("Location").orElse("");
        String page = get(client, base, new Visitor(cookie, "", 0), location.equals("/compte/nouveau-mot-de-passe") ? location : "/compte").body();
        Matcher csrf = CSRF.matcher(page);
        assertTrue(csrf.find(), page);
        return new Visitor(cookie, csrf.group(1), 0);
    }

    /** Envoi d'un fichier (formulaire multipart) : {@code fields} en plus du jeton CSRF, puis le fichier {@code file}. */
    static HttpResponse<String> upload(HttpClient client, String base, Visitor visitor, String path, java.util.Map<String, String> fields, String file,
                                       String filename, String content) throws Exception {
        String boundary = "patriaTestBoundary";
        StringBuilder body = new StringBuilder("--" + boundary + "\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n" + visitor.csrf() + "\r\n");
        fields.forEach((name, value) -> body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(file).append("\"; filename=\"").append(filename)
                .append("\"\r\nContent-Type: text/csv\r\n\r\n").append(content).append("\r\n--").append(boundary).append("--\r\n");
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", visitor.cookie())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), java.nio.charset.StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** En-tete Location d'une redirection, vide sinon. */
    static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    static Visitor admin(HttpClient client, String base) throws Exception {
        return login(client, base, "admin", "secret");
    }

    /**
     * Cree le compte {@code login} (nom {@code name}), lui donne ces droits sur le foyer {@code household}
     * ({@code access} : formulaire de droits, ex. {@code access=on&manager=on}), se connecte et choisit son mot de
     * passe : {@code login + "-motdepasse"}.
     */
    static Visitor account(HttpClient client, String base, Visitor admin, String login, String name, long household, String access) throws Exception {
        return account(client, base, admin, login, name, "", household, access);
    }

    /** Comme {@link #account(HttpClient, String, Visitor, String, String, long, String)}, {@code extra} s'ajoutant a la creation (ex. {@code membre=1%3A1}). */
    static Visitor account(HttpClient client, String base, Visitor admin, String login, String name, String extra, long household, String access) throws Exception {
        HttpResponse<String> created = post(client, base, admin, "/administration/utilisateurs", "login=" + login + "&name=" + name + "&password=provisoire1"
                + (extra.isEmpty() ? "" : "&" + extra));
        String location = created.headers().firstValue("Location").orElseThrow(() -> new AssertionError(created.body()));
        long id = Long.parseLong(location.replaceAll(".*/utilisateur/(\\d+).*", "$1"));
        if (access != null) assertEquals(303, post(client, base, admin, "/administration/utilisateur/" + id + "/foyer/" + household, access).statusCode());
        Visitor first = login(client, base, login, "provisoire1");
        String password = login + "-motdepasse";
        assertEquals(303, post(client, base, first, "/compte/mot-de-passe", "current=provisoire1&password=" + password + "&confirm=" + password).statusCode());
        Visitor visitor = login(client, base, login, password);
        return new Visitor(visitor.cookie(), visitor.csrf(), id);
    }

    /** Gestionnaire du foyer principal : il saisit tout. */
    static Visitor manager(HttpClient client, String base) throws Exception {
        return account(client, base, admin(client, base), "gestion", "Gestion", 1, "access=on&manager=on");
    }
}
