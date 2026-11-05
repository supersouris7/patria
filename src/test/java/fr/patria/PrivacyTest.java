package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.storage.Encryption;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chiffrement des bases, alerte HTTP et accueil, a travers le vrai serveur. */
class PrivacyTest {
    private static final String KEY = "une-cle-de-test-assez-longue";
    @TempDir
    Path directory;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private String base;

    private record Visitor(String cookie, String csrf) {
    }

    private App start(String key) {
        App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, false, key, null));
        base = "http://127.0.0.1:" + app.port();
        return app;
    }

    private HttpResponse<String> get(Visitor visitor, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        String body = visitor == null ? form : "csrf=" + visitor.csrf() + (form.isEmpty() ? "" : "&" + form);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    private Visitor login(String login, String password) throws Exception {
        HttpResponse<String> logged = post(null, "/connexion", "login=" + login + "&password=" + password);
        assertEquals(303, logged.statusCode());
        String cookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(get(new Visitor(cookie, ""), "/compte").body());
        assertTrue(csrf.find());
        return new Visitor(cookie, csrf.group(1));
    }

    @Test
    void databasesAreEncryptedWithTheKey() throws Exception {
        Path main = directory.resolve("patria.sqlite3");
        // Installation existante en clair, avec une copie avant migration : tout est chiffre au demarrage avec la cle.
        try (App ignored = start(null)) {
            TestAccounts.Visitor admin = TestAccounts.admin(client, base);
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/foyers", "name=Foyer+Martin").statusCode());
            TestAccounts.Visitor manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&manager=on");
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/utilisateur/" + manager.id() + "/foyer/2", "access=on&manager=on").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/membres", "name=Paul&kind=PERSON").statusCode());
        }
        assertTrue(Encryption.isPlain(main));
        Files.copy(main, directory.resolve("patria.sqlite3.avant-migration-v12-20260101-000000"));
        try (App ignored = start(KEY)) {
            Visitor admin = login("gestion", "gestion-motdepasse");
            assertTrue(get(admin, "/administration/membres").body().contains("Paul"), "données relues avec la clé");
            assertEquals(303, post(admin, "/bien", "name=Studio&kind=APARTMENT&purchase=100000&purchase_date=2020-01-01&estimate=120000"
                    + "&estimate_date=2025-01-01&sale_fees_pct=8").statusCode());
            assertEquals("/administration/bien/1?ok=deplace", location(post(admin, "/administration/bien/1/foyer", "foyer=2")), "déplacement entre bases chiffrées");
            assertTrue(get(admin, "/confidentialite").body().contains("sont <b>chiffrées</b>"));
        }
        for (String file : new String[]{"patria.sqlite3", "foyer-2.sqlite3", App.ACCOUNTS_FILE, "patria.sqlite3.avant-migration-v12-20260101-000000"}) {
            assertFalse(Encryption.isPlain(directory.resolve(file)), file + " chiffré");
            assertFalse(new String(Files.readAllBytes(directory.resolve(file)), StandardCharsets.ISO_8859_1).contains("Paul"), file);
        }
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> start(null));
        assertTrue(missing.getMessage().contains("DB_ENCRYPTION_KEY"), missing.getMessage());
        assertThrows(IllegalStateException.class, () -> start("une-autre-cle-de-test-longue"));
        assertThrows(IllegalStateException.class, () -> new Config("secret", "admin", main, 0, TestData.CLOCK, false, "courte", null));
    }

    /** Requete brute : le client HTTP de Java ne laisse pas choisir l'en-tete Host. */
    private String rawGet(String path, String host, String cookie) throws Exception {
        URI uri = URI.create(base);
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nCookie: " + cookie + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            for (String line; (line = reader.readLine()) != null; ) response.append(line).append('\n');
            return response.toString();
        }
    }

    @Test
    void httpIsFlaggedUntilAcknowledged() throws Exception {
        try (App ignored = start(null)) {
            Visitor admin = login("admin", "secret");
            assertFalse(get(admin, "/administration/foyers").body().contains("security-overlay"), "localhost : rien ne passe par le réseau");
            String overHttp = rawGet("/administration/foyers", "patria.lan:8080", admin.cookie());
            assertTrue(overHttp.contains("Connexion non chiffrée (HTTP)") && overHttp.contains("name=\"suite\" value=\"/administration/foyers\""), overHttp);
            assertFalse(rawGet("/aide/https", "patria.lan", admin.cookie()).contains("security-overlay"), "pas par-dessus la page d'aide");
            assertTrue(rawGet("/connexion", "patria.lan", "").contains("http-warning"), "alerte avant de taper le mot de passe");
            assertEquals(200, get(null, "/aide/https").statusCode(), "aide lisible sans connexion");
            assertEquals("/administration/foyers", location(post(admin, "/securite/http-compris", "suite=%2Fadministration%2Ffoyers")));
            assertFalse(rawGet("/administration/foyers", "patria.lan", admin.cookie()).contains("security-overlay"), "lue pour cette session");
            Visitor other = login("admin", "secret");
            assertTrue(rawGet("/administration/foyers", "patria.lan", other.cookie()).contains("security-overlay"), "revient à chaque connexion");
            assertTrue(get(other, "/administration/securite").body().contains("Connexion locale (localhost)"), "état de l'installation : client local");
            HttpResponse<String> https = client.send(HttpRequest.newBuilder(URI.create(base + "/administration/foyers")).header("Cookie", other.cookie())
                    .header("X-Forwarded-Proto", "https").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(https.body().contains("security-overlay"), "derrière un reverse proxy HTTPS");
        }
    }

    @Test
    void firstLoginShowsTheWelcomeOnce() throws Exception {
        try (App ignored = start(null)) {
            assertEquals("/bienvenue", location(post(null, "/connexion", "login=admin&password=secret")));
            Visitor admin = login("admin", "secret");
            for (int step = 1; step <= 4; step++) assertEquals(200, get(admin, "/bienvenue?etape=" + step).statusCode());
            assertTrue(get(admin, "/bienvenue?etape=99").body().contains("Soutenir"), "étape bornée");
            assertTrue(get(admin, "/bienvenue?etape=3").body().contains("DB_ENCRYPTION_KEY"), "l'administrateur voit que les bases ne sont pas chiffrées");
            assertTrue(get(admin, "/bienvenue?etape=1").body().contains("ne voyez aucune donnée financière"), "accueil propre à l'administrateur");
            assertEquals("/administration/foyers", location(post(admin, "/bienvenue/terminer", "")));
            assertEquals("/", location(post(null, "/connexion", "login=admin&password=secret")), "une seule fois");
            assertTrue(get(admin, "/administration/foyers").body().contains("class=\"version\""), "version en pied de page");

            TestAccounts.Visitor manager = TestAccounts.account(client, base, TestAccounts.admin(client, base), "gestion", "Gestion", 1, "access=on&manager=on");
            Visitor gestion = new Visitor(manager.cookie(), manager.csrf());
            assertEquals("/assistant", location(post(gestion, "/bienvenue/terminer", "")), "le gestionnaire commence par le guide");
            String home = get(gestion, "/").body();
            assertTrue(home.contains("Configuration terminée à 0 %"), "encadré de progression");
            assertEquals("/", location(post(gestion, "/assistant/encadre/ignorer", "")));
            assertFalse(get(gestion, "/").body().contains("Configuration terminée"), "ignoré");
        }
    }
}
