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
            Visitor manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&manager=on");
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
    void redactionIsLocalAndLinkedBeforeBothImports() throws Exception {
        try (App ignored = start(null)) {
            HttpResponse<String> help = get(null, "/aide/caviardage");
            assertEquals(200, help.statusCode());
            assertTrue(help.body().contains("Aucun document n'est envoyé"));
            assertTrue(help.body().contains("redaction.mjs?v="));
            assertFalse(help.body().contains("<form"), "aucun formulaire d'envoi du document sur la page publique");
            assertTrue(help.headers().firstValue("Content-Security-Policy").orElseThrow().contains("'wasm-unsafe-eval'"));
            assertFalse(get(null, "/confidentialite").headers().firstValue("Content-Security-Policy").orElseThrow().contains("'wasm-unsafe-eval'"));
            for (String asset : new String[]{"/js/redaction.mjs", "/js/redaction-pdf.mjs", "/vendor/pdfjs/build/pdf.min.mjs", "/vendor/pdfjs/build/pdf.worker.min.mjs"}) {
                HttpResponse<String> response = get(null, asset);
                assertEquals(200, response.statusCode(), asset);
                assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("javascript"));
            }
            assertEquals(200, get(null, "/vendor/pdfjs/wasm/openjpeg.wasm").statusCode());
            Visitor admin = TestAccounts.admin(client, base);
            Visitor manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&manager=on");
            Visitor visitor = manager;
            assertTrue(get(visitor, "/administration/depenses").body().contains("href=\"/aide/caviardage\""));
            HttpResponse<String> documentStep = get(visitor, "/assistant/documents");
            String preparation = documentStep.body();
            assertTrue(preparation.contains("Préparer les documents avant un import") && preparation.contains("href=\"/aide/caviardage\""));
            assertTrue(preparation.contains("id=\"redaction-file\"") && preparation.contains("redaction.mjs?v="), "outil integre, pas seulement un lien");
            assertTrue(documentStep.headers().firstValue("Content-Security-Policy").orElseThrow().contains("'wasm-unsafe-eval'"));
            assertTrue(help.body().contains("Contrôle Patria avant partage") && help.body().contains("Pas d'OCR"));
            assertEquals(303, post(visitor, "/bien", "name=Test&kind=APARTMENT&purchase=100000&purchase_date=2020-01-01&estimate=120000&estimate_date=2025-01-01&sale_fees_pct=8").statusCode());
            assertTrue(get(visitor, "/bien/1/prets/nouveau").body().contains("href=\"/aide/caviardage\""));
            assertTrue(get(null, "/aide/chiffrement").body().contains("Oubli au premier lancement"));
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

            Visitor manager = TestAccounts.account(client, base, TestAccounts.admin(client, base), "gestion", "Gestion", 1, "access=on&manager=on");
            Visitor gestion = manager;
            assertEquals("/assistant", location(post(gestion, "/bienvenue/terminer", "")), "le gestionnaire commence par le guide");
            String home = get(gestion, "/").body();
            assertTrue(home.contains("Configuration terminée à 0 %"), "encadré de progression");
            assertEquals("/", location(post(gestion, "/assistant/encadre/ignorer", "")));
            assertFalse(get(gestion, "/").body().contains("Configuration terminée"), "ignoré");
        }
    }
}
