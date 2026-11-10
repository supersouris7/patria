// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
            Visitor manager = TestAccounts.manager(client, base);
            // Second foyer : lien « créer son foyer » de l'administrateur, accepté par le gestionnaire.
            var link = java.util.regex.Pattern.compile("jeton=([A-Za-z0-9_-]+)").matcher(TestAccounts.post(client, base, admin, "/administration/foyers/lien", "").body());
            assertTrue(link.find());
            assertEquals(303, TestAccounts.post(client, base, manager, "/invitation/rejoindre", "jeton=" + link.group(1) + "&foyer=Foyer+Martin").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/foyer/choisir", "foyer=1").statusCode());
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
            String licence = get(admin, "/licence").body();
            assertTrue(licence.contains("Elastic License 2.0") && licence.contains("William Iv") && licence.contains("hosted or managed"));
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
            assertFalse(get(admin, "/administration/securite").body().contains("security-overlay"), "localhost : rien ne passe par le réseau");
            String overHttp = rawGet("/administration/securite", "patria.lan:8080", admin.cookie());
            assertTrue(overHttp.contains("Connexion non chiffrée (HTTP)") && overHttp.contains("name=\"suite\" value=\"/administration/securite\""), overHttp);
            assertFalse(rawGet("/aide/https", "patria.lan", admin.cookie()).contains("security-overlay"), "pas par-dessus la page d'aide");
            assertTrue(rawGet("/connexion", "patria.lan", "").contains("http-warning"), "alerte avant de taper le mot de passe");
            assertEquals(200, get(null, "/aide/https").statusCode(), "aide lisible sans connexion");
            assertTrue(get(null, "/aide/chiffrement").body().contains("openssl rand -base64 32"), "pas à pas du chiffrement");
            assertEquals("/administration/securite", location(post(admin, "/securite/http-compris", "suite=%2Fadministration%2Fsecurite")));
            assertFalse(rawGet("/administration/securite", "patria.lan", admin.cookie()).contains("security-overlay"), "lue pour cette session");
            Visitor other = login("admin", "secret");
            assertTrue(rawGet("/administration/securite", "patria.lan", other.cookie()).contains("security-overlay"), "revient à chaque connexion");
            assertTrue(get(other, "/administration/securite").body().contains("Connexion locale (localhost)"), "état de l'installation : client local");
            HttpResponse<String> https = client.send(HttpRequest.newBuilder(URI.create(base + "/administration/securite")).header("Cookie", other.cookie())
                    .header("X-Forwarded-Proto", "https").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(https.body().contains("security-overlay"), "derrière un reverse proxy HTTPS");
        }
    }

    @Test
    void promptsStayAndALocalAiConvertsDroppedDocuments() throws Exception {
        java.util.List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer ollama = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/tags", exchange -> {
            byte[] answer = "{\"models\":[{\"name\":\"qwen2.5:3b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        ollama.createContext("/api/generate", exchange -> {
            prompts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{\"response\":\"Voici :\\n```csv\\nNetflix;Télécom et abonnements;13,49;Mensuel;01/01/2026;;Abonnement\\n```\",\"done\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        ollama.start();
        try (App ignored = start(null)) {
            assertFalse(get(null, "/aide/caviardage").statusCode() == 200, "outil de caviardage retiré");
            HttpResponse<String> login = get(null, "/connexion");
            assertEquals("DENY", login.headers().firstValue("X-Frame-Options").orElseThrow());
            assertTrue(login.headers().firstValue("Permissions-Policy").orElseThrow().contains("camera=()"));
            assertEquals("same-origin", login.headers().firstValue("Cross-Origin-Opener-Policy").orElseThrow());
            assertTrue(login.headers().firstValue("Strict-Transport-Security").isEmpty(), "HSTS seulement en HTTPS");
            Visitor admin = TestAccounts.admin(client, base);
            Visitor manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            String expenses = get(manager, "/administration/depenses").body();
            assertTrue(expenses.contains("Copier le prompt IA") && expenses.contains("Attention aux données que vous confiez à une IA commerciale"));
            assertTrue(expenses.contains("Utilisez une conversation temporaire") && expenses.contains("Ne rendez jamais la conversation publique"), "réglages conseillés");
            assertFalse(expenses.contains("name=\"document\""), "pas d'IA locale par défaut");

            assertEquals(303, post(admin, "/administration/securite/ia", "ia=on&url=http%3A%2F%2F127.0.0.1%3A" + ollama.getAddress().getPort() + "&modele=qwen2.5%3A3b")
                    .statusCode());
            assertTrue(get(admin, "/administration/securite").body().contains("IA locale prête"));
            String withAi = get(manager, "/administration/depenses").body();
            assertTrue(withAi.contains("name=\"document\"") && withAi.contains("Copier le prompt IA"), "document à déposer, prompt toujours là");
            String document = get(manager, "/assistant/documents").body();
            assertTrue(document.contains("prompt-universel") && document.contains("name=\"document\""), "documents à déposer pour le prompt universel");

            String boundary = "b0undary";
            String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n" + manager.csrf() + "\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"ia\"\r\n\r\ndepenses\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"releve.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                    + "12/01/2026 PRLV NETFLIX 13,49\r\n--" + boundary + "--\r\n";
            HttpResponse<String> preview = client.send(HttpRequest.newBuilder(URI.create(base + "/administration/depenses/import")).header("Cookie", manager.cookie())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, preview.statusCode(), preview.body());
            assertTrue(preview.body().contains("Netflix") && preview.body().contains("name=\"importer_1\""), "aperçu des lignes converties");
            assertTrue(prompts.getFirst().contains("PRLV NETFLIX") && prompts.getFirst().contains("qwen2.5:3b"), "document et modèle envoyés à l'IA locale");
        } finally {
            fr.patria.ai.LocalAi.configure(null);
            fr.patria.ui.Components.useLocalAi(false);
            ollama.stop(0);
        }
    }

    @Test
    void firstLoginShowsTheWelcomeOnce() throws Exception {
        try (App ignored = start(null)) {
            assertEquals("/bienvenue", location(post(null, "/connexion", "login=admin&password=secret")));
            Visitor admin = login("admin", "secret");
            String welcome = get(admin, "/bienvenue").body();
            assertTrue(welcome.contains("DB_ENCRYPTION_KEY"), "l'administrateur voit que les bases ne sont pas chiffrées");
            assertTrue(welcome.contains("ne créez aucun compte") && !welcome.contains("Ko-fi"), "accueil propre à l'administrateur, sans don");
            assertEquals("/administration/securite?visite=1", location(post(admin, "/bienvenue/terminer", "visite=1")));
            String tour = get(admin, "/administration/securite?visite=1").body();
            assertTrue(tour.contains("<template id=\"visite\"") && tour.contains("Vous supervisez, sans saisir"), "visite de l'administrateur");
            assertFalse(get(admin, "/administration/securite").body().contains("id=\"visite\""), "pas de visite sans la demander");
            assertEquals("/", location(post(null, "/connexion", "login=admin&password=secret")), "une seule fois");
            assertTrue(get(admin, "/administration/securite").body().contains("class=\"version\""), "version en pied de page");

            Visitor manager = TestAccounts.account(client, base, TestAccounts.admin(client, base), "gestion", "Gestion", 1, "access=on&all=on");
            Visitor gestion = manager;
            assertEquals("/?visite=1", location(post(gestion, "/bienvenue/terminer", "visite=1")), "la visite commence sur le budget");
            String visit = get(gestion, "/?visite=1").body();
            assertTrue(visit.contains("Ouvrir le Guide de saisie") && visit.contains("Mon foyer et les droits"), "visite du gestionnaire, puis le guide");
            assertEquals("/", location(post(gestion, "/bienvenue/terminer", "passer=1")), "passer : sans visite");
            String home = get(gestion, "/").body();
            assertTrue(home.contains("Guide de saisie : 0 % des étapes faites"), "encadré de progression");
            assertEquals("/", location(post(gestion, "/assistant/encadre/ignorer", "")));
            assertFalse(get(gestion, "/").body().contains("Configuration terminée"), "ignoré");
        }
    }
}
