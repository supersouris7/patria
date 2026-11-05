package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.Totp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Double authentification : activation, connexion en deux etapes, appareil retenu, retrait par l'administrateur. */
class TwoFactorTest {
    @TempDir
    Path directory;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private String base;
    private final long now = TestData.CLOCK.instant().getEpochSecond();

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    /** Connexion ouverte : vers l'accueil, ou l'accueil de la premiere connexion. */
    private static boolean opened(String location) {
        return location.equals("/") || location.equals("/bienvenue");
    }

    private static String cookie(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "=")).map(value -> value.split(";")[0]).findFirst().orElse(null);
    }

    private HttpResponse<String> postRaw(String path, String form, String cookies) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (cookies != null) builder.header("Cookie", cookies);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Active la double authentification du compte connecte ; renvoie son secret. */
    private String enable(TestAccounts.Visitor visitor) throws Exception {
        String setup = TestAccounts.get(client, base, visitor, "/compte/double-authentification").body();
        assertTrue(setup.contains("<svg class=\"qr-code\""), "QR code dessiné");
        Matcher secret = Pattern.compile("<code class=\"secret\">([A-Z2-7 ]+)</code>").matcher(setup);
        assertTrue(secret.find());
        String key = secret.group(1).replace(" ", "");
        assertEquals(400, TestAccounts.post(client, base, visitor, "/compte/double-authentification/activer", "code=000000").statusCode(), "code faux");
        assertEquals("/compte?ok=double-authentification-active", location(TestAccounts.post(client, base, visitor, "/compte/double-authentification/activer",
                "code=" + Totp.code(key, Totp.step(now)))));
        return key;
    }

    @Test
    void secondStepAtLoginRememberedDeviceAndAdminRemoval() throws Exception {
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK))) {
            base = "http://127.0.0.1:" + app.port();
            TestAccounts.Visitor admin = TestAccounts.admin(client, base);
            assertTrue(TestAccounts.get(client, base, admin, "/compte").body().contains("Activer la double authentification"));
            TestAccounts.Visitor paul = TestAccounts.account(client, base, admin, "paul", "Paul", 1, "access=on&whole=on");
            assertFalse(TestAccounts.get(client, base, paul, "/").body().contains("class=\"last-login"), "horloge retirée de l'en-tête");
            assertTrue(TestAccounts.get(client, base, paul, "/compte").body().contains("id=\"connexions\""), "historique de sécurité conservé dans Mon compte");
            String key = enable(paul);
            assertTrue(TestAccounts.get(client, base, admin, "/administration/securite").body().contains("Activée le 15/06/2026"), "supervision : activée");

            // Connexion : mot de passe, puis code
            HttpResponse<String> first = postRaw("/connexion", "login=paul&password=paul-motdepasse", null);
            assertTrue(location(first).startsWith("/connexion/code?jeton="), "le code est demandé");
            assertEquals(null, cookie(first, "patria_session"), "pas de session avant le code");
            String token = location(first).substring(location(first).indexOf('=') + 1);
            assertTrue(postRaw("/connexion/code", "jeton=" + token + "&code=" + Totp.code(key, Totp.step(now)), null).headers().firstValue("Location").orElse("")
                    .contains("erreur"), "code déjà utilisé à l'activation : refusé");
            HttpResponse<String> done = postRaw("/connexion/code", "jeton=" + token + "&code=" + Totp.code(key, Totp.step(now) + 1) + "&retenir=on", null);
            assertTrue(opened(location(done)));
            String device = cookie(done, "patria_appareil");
            assertTrue(device != null && cookie(done, "patria_session") != null, "session et appareil retenu");
            assertTrue(opened(location(postRaw("/connexion", "login=paul&password=paul-motdepasse", device))), "appareil retenu : pas de code");
            assertTrue(location(postRaw("/connexion", "login=paul&password=paul-motdepasse", null)).startsWith("/connexion/code"), "autre appareil : code");
            assertTrue(TestAccounts.get(client, base, admin, "/administration/securite").body().contains("paul (code 2FA)"), "code faux au journal");

            // Telephone perdu : l'administrateur retire la double authentification, Paul en est averti
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/utilisateur/" + paul.id() + "/double-authentification/retirer", "").statusCode());
            assertTrue(opened(location(postRaw("/connexion", "login=paul&password=paul-motdepasse", device))), "plus de code, appareil oublié");
            TestAccounts.Visitor again = TestAccounts.login(client, base, "paul", "paul-motdepasse");
            String home = TestAccounts.get(client, base, again, "/").body();
            assertTrue(home.contains("L&#39;administrateur a retiré votre double authentification le 15/06/2026") || home.contains("a retiré votre double authentification"), home);
            assertEquals(303, TestAccounts.post(client, base, again, "/compte/avis-lu", "").statusCode());
            assertFalse(TestAccounts.get(client, base, again, "/").body().contains("a retiré votre double authentification"), "avis lu");
            assertTrue(TestAccounts.get(client, base, admin, "/administration/securite").body().contains("retirée (l&#39;utilisateur en est averti)"));
        }
    }

    @Test
    void administratorTwoFactorAndItsReset() throws Exception {
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK))) {
            base = "http://127.0.0.1:" + app.port();
            TestAccounts.Visitor admin = TestAccounts.admin(client, base);
            String key = enable(admin);
            assertTrue(location(postRaw("/connexion", "login=admin&password=secret", null)).startsWith("/connexion/code"), "l'administrateur aussi");
            assertEquals(400, TestAccounts.post(client, base, admin, "/compte/double-authentification/desactiver", "code=123456").statusCode());
            assertTrue(key.length() > 20);
        }
        // Telephone perdu : ADMIN_2FA_RESET=true au demarrage
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, false, null, null, true))) {
            base = "http://127.0.0.1:" + app.port();
            assertTrue(opened(location(postRaw("/connexion", "login=admin&password=secret", null))), "plus de code demandé");
        }
    }
}
