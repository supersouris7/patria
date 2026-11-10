package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** E-mails : reglage par l'administrateur, inscription et invitation avec e-mail confirme, mot de passe oublie. */
class MailTest {
    @TempDir
    Path directory;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final BlockingQueue<String> mails = new LinkedBlockingQueue<>();

    /** Faux serveur SMTP sans chiffrement : accepte tout et garde le corps decode de chaque message. */
    private ServerSocket fakeSmtp() throws Exception {
        ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream out = socket.getOutputStream();
                    out.write("220 test\r\n".getBytes(StandardCharsets.US_ASCII));
                    StringBuilder data = null;
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (data != null) {
                            if (line.equals(".")) {
                                // Version texte (premiere partie base64), et la version HTML doit exister
                                String message = data.toString();
                                int start = message.indexOf("Content-Transfer-Encoding: base64\r\n\r\n") + "Content-Transfer-Encoding: base64\r\n\r\n".length();
                                int end = message.indexOf("\r\n--", start);
                                String body = message.substring(start, end < 0 ? message.length() : end).replace("\r\n", "");
                                String text = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
                                mails.add(message.contains("text/html") ? text : "SANS VERSION HTML " + text);
                                data = null;
                                out.write("250 ok\r\n".getBytes(StandardCharsets.US_ASCII));
                            } else {
                                data.append(line).append("\r\n");
                            }
                            continue;
                        }
                        if (line.startsWith("DATA")) {
                            data = new StringBuilder();
                            out.write("354 go\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else if (line.startsWith("QUIT")) {
                            out.write("221 bye\r\n".getBytes(StandardCharsets.US_ASCII));
                            break;
                        } else {
                            out.write("250 ok\r\n".getBytes(StandardCharsets.US_ASCII));
                        }
                    }
                } catch (Exception closed) {
                    // Fin du test.
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private String link() throws Exception {
        String mail = mails.poll(10, TimeUnit.SECONDS);
        assertTrue(mail != null, "e-mail envoyé");
        Matcher link = Pattern.compile("https?://[^/\\s]+(/[^\\s]*jeton=[A-Za-z0-9_-]+)").matcher(mail);
        assertTrue(link.find(), mail);
        return link.group(1);
    }

    @Test
    void emailsConfirmAccountsAndResetPasswords() throws Exception {
        try (ServerSocket smtp = fakeSmtp(); App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK))) {
            String base = "http://127.0.0.1:" + app.port();
            TestAccounts.Visitor admin = TestAccounts.admin(client, base);
            TestAccounts.Visitor manager = TestAccounts.manager(client, base);
            assertFalse(TestAccounts.get(client, base, null, "/connexion").body().contains("Mot de passe oublié"), "sans envoi réglé, pas de lien");
            TestAccounts.post(client, base, admin, "/administration/securite/email", "host=127.0.0.1&port=" + smtp.getLocalPort()
                    + "&securite=NONE&expediteur=patria%40test.fr&adresse=" + base + "&exiger=on");
            TestAccounts.post(client, base, admin, "/administration/securite/email/test", "a=admin%40test.fr");
            assertTrue(mails.poll(10, TimeUnit.SECONDS).contains("fonctionne"), "e-mail de test");
            assertTrue(TestAccounts.get(client, base, null, "/connexion").body().contains("Mot de passe oublié"));

            // Inscription libre : e-mail exige, compte et foyer crees, e-mail a confirmer avant tout, puis 2FA proposee
            TestAccounts.post(client, base, admin, "/administration/inscriptions", "mode=open");
            assertEquals(400, TestAccounts.post(client, base, null, "/inscription", "name=Lea&login=lea&password=leapasse1&confirm=leapasse1").statusCode(), "e-mail exigé");
            HttpResponse<String> signed = TestAccounts.post(client, base, null, "/inscription",
                    "name=Lea&login=lea&email=lea%40test.fr&password=leapasse1&confirm=leapasse1&foyer=Foyer+Lea");
            assertEquals("/compte/securiser", TestAccounts.location(signed));
            TestAccounts.Visitor lea = new TestAccounts.Visitor(signed.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], "", 0);
            assertEquals("/compte/email", TestAccounts.location(TestAccounts.get(client, base, lea, "/")), "rien d'autre avant l'e-mail");
            assertEquals("/compte/securiser", TestAccounts.location(TestAccounts.get(client, base, null, link())), "e-mail confirmé, puis la double authentification");
            assertEquals(200, TestAccounts.get(client, base, lea, "/foyer").statusCode(), "gestionnaire de son foyer");

            // Invitation par e-mail : le gestionnaire du foyer 1 invite Paul, qui cree son compte depuis le lien recu
            assertTrue(TestAccounts.post(client, base, manager, "/foyer/invitations", "membre=nouveau&name=Paul&email=paul%40test.fr&whole=on").body()
                    .contains("Invitation envoyée"));
            String invitation = link().replaceAll(".*jeton=", "");
            HttpResponse<String> joined = TestAccounts.post(client, base, null, "/invitation", "jeton=" + invitation
                    + "&name=Paul&login=paul&email=paul%40test.fr&password=paulpasse1&confirm=paulpasse1");
            TestAccounts.Visitor first = new TestAccounts.Visitor(joined.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], "", 0);
            assertEquals("/compte/email", TestAccounts.location(TestAccounts.get(client, base, first, "/")));
            assertEquals("/compte/securiser", TestAccounts.location(TestAccounts.get(client, base, null, link())));
            assertEquals(200, TestAccounts.get(client, base, first, "/").statusCode(), "accès ouvert");
            assertEquals(400, TestAccounts.post(client, base, null, "/invitation", "jeton=" + invitation
                    + "&name=Autre&login=autre&email=a%40test.fr&password=autrepasse1&confirm=autrepasse1").statusCode(), "invitation à usage unique");

            // Mot de passe oublie : meme reponse pour un inconnu, lien a usage unique, sessions fermees
            assertTrue(TestAccounts.post(client, base, null, "/connexion/oubli", "compte=inconnu").body().contains("Si un compte correspond"));
            assertTrue(mails.poll(1, TimeUnit.SECONDS) == null, "rien pour un inconnu");
            TestAccounts.post(client, base, null, "/connexion/oubli", "compte=paul%40test.fr");
            String reset = link();
            String token = reset.replaceAll(".*jeton=", "");
            assertTrue(TestAccounts.get(client, base, null, reset).body().contains("Nouveau mot de passe"));
            assertEquals(400, TestAccounts.post(client, base, null, "/connexion/reinitialiser", "jeton=" + token + "&password=court&confirm=court").statusCode());
            assertEquals("/connexion?reinitialise", TestAccounts.location(TestAccounts.post(client, base, null, "/connexion/reinitialiser",
                    "jeton=" + token + "&password=nouveaupasse1&confirm=nouveaupasse1")));
            assertEquals(400, TestAccounts.post(client, base, null, "/connexion/reinitialiser", "jeton=" + token + "&password=autrepasse1&confirm=autrepasse1").statusCode(),
                    "lien déjà utilisé");
            assertEquals("/connexion", TestAccounts.location(TestAccounts.get(client, base, first, "/")).replaceAll("\\?.*", ""), "anciennes sessions fermées");
            TestAccounts.login(client, base, "paul", "nouveaupasse1");
        }
    }
}
