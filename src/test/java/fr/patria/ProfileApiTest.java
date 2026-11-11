// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profil du compte (photo ronde), jetons d'API en lecture seule, revenu fiscal de reference et LEP. */
class ProfileApiTest {
    @TempDir
    Path directory;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void profilePhotoApiTokenAndTaxIncome() throws Exception {
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, true))) {
            String base = "http://127.0.0.1:" + app.port();
            var admin = TestAccounts.admin(client, base);
            var manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());

            // Profil : prenom + nom deviennent le nom affiche ; le membre represente apparait dans le tableau des droits.
            assertEquals(400, TestAccounts.post(client, base, manager, "/compte/profil", "prenom=Paul&nom=Durand&naissance=2999-01-01").statusCode(), "naissance future");
            assertEquals(400, TestAccounts.post(client, base, manager, "/compte/profil", "prenom=Paul&telephone=abc").statusCode(), "téléphone invalide");
            assertEquals(303, TestAccounts.post(client, base, manager, "/compte/profil", "prenom=Paul&nom=Durand&naissance=1985-04-12&telephone=06+12+34+56+78&membre=1")
                    .statusCode());
            String account = TestAccounts.get(client, base, manager, "/compte").body();
            assertTrue(account.contains("value=\"Durand\"") && account.contains("value=\"1985-04-12\"") && account.contains("<option value=\"1\" selected>Paul"));
            assertTrue(account.contains("class=\"avatar avatar-small\"") && account.contains(">PD</span>"), "initiales dans l'en-tête");
            assertTrue(TestAccounts.get(client, base, manager, "/foyer").body().contains("c&#39;est Paul dans le budget"), "membre représenté dans le tableau des droits");

            // Photo : PNG reencode en JPEG carre (metadonnees retirees) ; visible du compte, pas d'un compte d'un autre foyer.
            assertEquals(400, photo(base, manager, "pas une image".getBytes(StandardCharsets.UTF_8)).statusCode());
            assertEquals(303, photo(base, manager, png()).statusCode());
            long id = TestAccounts.userId(client, base, "gestion");
            HttpResponse<byte[]> image = client.send(HttpRequest.newBuilder(URI.create(base + "/compte/avatar/" + id)).header("Cookie", manager.cookie()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, image.statusCode());
            assertEquals("image/jpeg", image.headers().firstValue("Content-Type").orElse(""));
            assertEquals(0xFF, image.body()[0] & 0xFF);
            assertTrue(TestAccounts.get(client, base, manager, "/compte").body().contains("src=\"/compte/avatar/" + id + "\""));
            assertEquals(404, TestAccounts.get(client, base, manager, "/compte/avatar/999").statusCode(), "compte sans foyer commun : rien");
            assertTrue(TestAccounts.get(client, base, admin, "/administration/utilisateur/" + id).body().contains("06 12 34 56 78"), "l'administrateur voit le téléphone");

            // Jeton d'API : affiche une fois, lecture seule, revocable.
            String created = TestAccounts.post(client, base, manager, "/compte/api", "nom=Homepage").body();
            Matcher token = Pattern.compile("(patria_[A-Za-z0-9_-]+)").matcher(created);
            assertTrue(token.find(), "jeton affiché une fois");
            assertFalse(TestAccounts.get(client, base, manager, "/compte").body().contains(token.group(1)), "plus jamais affiché ensuite");
            HttpResponse<String> summary = api(base, token.group(1));
            assertEquals(200, summary.statusCode());
            assertTrue(summary.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            assertTrue(summary.body().contains("\"reste_a_vivre\":") && summary.body().contains("\"patrimoine_net\":") && summary.body().contains("\"alertes\":"), summary.body());
            assertEquals(401, api(base, "patria_faux").statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/resume")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/resume")).header("Authorization", "Bearer " + token.group(1))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "lecture seule");
            assertEquals(303, client.send(HttpRequest.newBuilder(URI.create(base + "/epargne")).header("Authorization", "Bearer " + token.group(1)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "le jeton n'ouvre que /api/ : ailleurs, la connexion");
            Matcher row = Pattern.compile("/compte/api/(\\d+)/revoquer").matcher(TestAccounts.get(client, base, manager, "/compte").body());
            assertTrue(row.find());
            assertEquals(303, TestAccounts.post(client, base, manager, "/compte/api/" + row.group(1) + "/revoquer", "").statusCode());
            assertEquals(401, api(base, token.group(1)).statusCode(), "révoqué");

            // Revenu fiscal de reference : eligibilite au LEP calculee (plafond 1 part + demi-parts).
            assertEquals(303, TestAccounts.post(client, base, manager, "/epargne/fiscal", "rfr=30+000&annee=2026&parts=1,5").statusCode());
            String savings = TestAccounts.get(client, base, manager, "/epargne").body();
            assertTrue(savings.contains("Revenu au-dessus du plafond du LEP") && savings.contains("Vos intérêts") && !savings.contains("Pouvoir d&#39;achat"),
                    "30 000 € pour 1,5 part : au-dessus de 29 177 €");
            assertEquals(303, TestAccounts.post(client, base, manager, "/epargne/fiscal", "rfr=28000&annee=2026&parts=1,5").statusCode());
            assertTrue(TestAccounts.get(client, base, manager, "/epargne").body().contains("Revenu sous le plafond du LEP"));
            assertEquals(400, TestAccounts.post(client, base, manager, "/epargne/fiscal", "rfr=28000&annee=2026&parts=1,3").statusCode(), "parts par quart");
            String imported = TestAccounts.post(client, base, manager, "/assistant/documents/importer", "texte=FISCAL%3B40+000%3B2026%3B2").body();
            assertTrue(imported.contains("Revenu fiscal de référence enregistré"), "ligne FISCAL de l'IA");
            assertTrue(TestAccounts.get(client, base, manager, "/assistant/impots").body().contains("value=\"40000\""), "repris à l'étape Impôts");
        }
    }

    private HttpResponse<String> api(String base, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/resume")).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> photo(String base, TestAccounts.Visitor visitor, byte[] image) throws Exception {
        String boundary = "patriaPhoto";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n" + visitor.csrf() + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"moi.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(base + "/compte/photo")).header("Cookie", visitor.cookie())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] png() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(120, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
