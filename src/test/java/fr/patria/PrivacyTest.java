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
import fr.patria.TestAccounts.Visitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chiffrement des bases, alerte HTTP et accueil, a travers le vrai serveur. */
class PrivacyTest {
    private static final String KEY = "une-cle-de-test-assez-longue";
    @TempDir
    Path directory;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private String base;

    private App start(String key) {
        App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, false, key, null));
        base = "http://127.0.0.1:" + app.port();
        return app;
    }

    private HttpResponse<String> get(Visitor visitor, String path) throws Exception {
        return TestAccounts.get(client, base, visitor, path);
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        return TestAccounts.post(client, base, visitor, path, form);
    }

    private static String location(HttpResponse<String> response) {
        return TestAccounts.location(response);
    }

    private Visitor login(String login, String password) throws Exception {
        return TestAccounts.login(client, base, login, password);
    }

    @Test
    void databasesAreEncryptedWithTheKey() throws Exception {
        Path main = directory.resolve("patria.sqlite3");
        // Installation existante en clair, avec une copie avant migration : tout est chiffre au demarrage avec la cle.
        try (App ignored = start(null)) {
            Visitor admin = TestAccounts.admin(client, base);
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/foyers", "name=Foyer+Martin").statusCode());
            Visitor manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/utilisateur/" + manager.id() + "/foyer/2", "access=on&all=on").statusCode());
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
        }
        assertTrue(Encryption.isPlain(main));
        Files.copy(main, directory.resolve("patria.sqlite3.avant-migration-v12-20260101-000000"));
        try (App ignored = start(KEY)) {
            Visitor admin = login("gestion", "gestion-motdepasse");
            assertTrue(get(admin, "/administration/revenus").body().contains("Paul"), "données relues avec la clé");
            assertEquals(303, post(admin, "/bien", "name=Studio&kind=APARTMENT&purchase=100000&purchase_date=2020-01-01&estimate=120000"
                    + "&estimate_date=2025-01-01&sale_fees_pct=8").statusCode());
            assertEquals("/administration/bien/1?ok=deplace", location(post(admin, "/administration/bien/1/foyer", "foyer=2")), "déplacement entre bases chiffrées");
            assertTrue(get(admin, "/confidentialite").body().contains("sont <b>chiffrées</b>"));
            assertTrue(get(null, "/aide/chiffrement").body().contains("Les bases de cette installation sont chiffrées"), "aide lisible sans connexion");
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
            assertTrue(get(null, "/aide/chiffrement").body().contains("openssl rand -base64 32"), "pas à pas du chiffrement");
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
    void withoutStirlingTheToolIsHiddenAndAdviceStays() throws Exception {
        try (App ignored = start(null)) {
            assertFalse(get(null, "/aide/caviardage").statusCode() == 200, "ancien outil retiré");
            assertFalse(get(null, "/vendor/pdfjs/build/pdf.min.mjs").statusCode() == 200);
            Visitor manager = TestAccounts.account(client, base, TestAccounts.admin(client, base), "gestion", "Gestion", 1, "access=on&all=on");
            assertEquals(404, get(manager, "/documents/preparer").statusCode());
            String expenses = get(manager, "/administration/depenses").body();
            assertFalse(expenses.contains("/documents/preparer"));
            assertTrue(expenses.contains("Caviardez une copie avec l'outil de votre choix"));
            assertTrue(get(manager, "/assistant/documents").body().contains("Caviardez une copie avec l'outil de votre choix"));
            assertTrue(get(null, "/aide/chiffrement").body().contains("Oubli au premier lancement"));
        }
    }

    @Test
    void stirlingRedactsACopyWithTheHouseholdNamesAndCleansIt() throws Exception {
        java.util.List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer stirling = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stirling.createContext("/", exchange -> {
            calls.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("X-API-KEY"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] answer = "%PDF-1.7 traite".getBytes(StandardCharsets.ISO_8859_1);
            if (exchange.getRequestURI().getPath().endsWith("auto-redact")) exchange.getResponseHeaders().set("X-Stirling-Redaction-Matches", "3");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        stirling.start();
        try (App ignored = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, false, null, null)
                .withStirling("http://127.0.0.1:" + stirling.getAddress().getPort() + "/", "cle-api"))) {
            base = "http://127.0.0.1:" + ignored.port();
            Visitor admin = TestAccounts.admin(client, base);
            Visitor manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Pascaline+Durand&kind=PERSON").statusCode());
            assertTrue(get(manager, "/administration/depenses").body().contains("href=\"/documents/preparer\""));
            String page = get(manager, "/documents/preparer").body();
            assertTrue(page.contains("Pascaline Durand") && page.contains("repere_IBAN"), "noms du foyer et repères proposés");

            HttpResponse<byte[]> refused = upload(manager, "mots=Pascaline", "pas un pdf".getBytes(StandardCharsets.UTF_8));
            assertEquals(400, refused.statusCode());
            assertTrue(calls.isEmpty(), "rien n'est envoyé à Stirling pour un fichier qui n'est pas un PDF");

            HttpResponse<byte[]> copy = upload(manager, "mots=Pascaline+Durand&repere_IBAN=on&images=on", "%PDF-1.4 original".getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(200, copy.statusCode(), new String(copy.body(), StandardCharsets.UTF_8));
            assertEquals("application/pdf", copy.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(copy.headers().firstValue("Content-Disposition").orElseThrow().contains("copie-caviardee.pdf"));
            assertEquals(java.util.List.of("/api/v1/security/auto-redact cle-api", "/api/v1/security/sanitize-pdf cle-api"), calls, "caviardage puis nettoyage, sans OCR");
            assertTrue(bodies.getFirst().contains("Pascaline\\s+Durand") && bodies.getFirst().contains("name=\"useRegex\"\r\n\r\ntrue")
                    && bodies.getFirst().contains("name=\"convertPDFToImage\"\r\n\r\ntrue"));
            assertTrue(bodies.get(1).contains("name=\"removeMetadata\"\r\n\r\ntrue"));
        } finally {
            stirling.stop(0);
        }
    }

    /** Envoi multipart vers « Preparer un document » : champs en {@code a=b&c=d} et le PDF. */
    private HttpResponse<byte[]> upload(Visitor visitor, String fields, byte[] pdf) throws Exception {
        String boundary = "test-boundary";
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.util.List<String> parts = new java.util.ArrayList<>(java.util.List.of("csrf=" + visitor.csrf()));
        parts.addAll(java.util.Arrays.asList(fields.split("&")));
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + pair[0] + "\"\r\n\r\n"
                    + java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8) + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"releve.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(pdf);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(base + "/documents/preparer")).header("Cookie", visitor.cookie())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
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

            Visitor manager = TestAccounts.account(client, base, TestAccounts.admin(client, base), "gestion", "Gestion", 1, "access=on&all=on");
            Visitor gestion = manager;
            assertEquals("/assistant", location(post(gestion, "/bienvenue/terminer", "")), "le gestionnaire commence par le guide");
            String home = get(gestion, "/").body();
            assertTrue(home.contains("Configuration terminée à 0 %"), "encadré de progression");
            assertEquals("/", location(post(gestion, "/assistant/encadre/ignorer", "")));
            assertFalse(get(gestion, "/").body().contains("Configuration terminée"), "ignoré");
        }
    }
}
