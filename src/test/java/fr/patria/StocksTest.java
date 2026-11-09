package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Page Bourse : ajout d'un titre avec creation du compte, achat cumule, import d'un releve, modification et suppression. */
class StocksTest {
    @TempDir
    Path directory;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void titlesAreAddedImportedEditedAndRemoved() throws Exception {
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK))) {
            String base = "http://127.0.0.1:" + app.port();
            TestAccounts.Visitor manager = TestAccounts.manager(client, base);
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
            String empty = TestAccounts.get(client, base, manager, "/bourse").body();
            assertTrue(empty.contains("Importer un relevé") && empty.contains("Ajouter un titre"), "page vide : les deux entrées");
            assertTrue(TestAccounts.get(client, base, manager, "/bourse/ajouter?symbole=cw8.pa&nom=ETF+Monde").body().contains("Nouveau compte"), "sans compte : on le crée");

            // Premier titre : le compte est cree (0 € d'especes), prix moyen par part
            String location = TestAccounts.location(TestAccounts.post(client, base, manager, "/bourse/ajouter",
                    "symbole=CW8.PA&nom=ETF+Monde&quantity=2&montant=500&prix=pru&compte=nouveau&compte_type=PEA&compte_nom=Trade+Republic&titulaire=1"));
            assertTrue(location.startsWith("/bourse?ok=enregistre#compte-"), location);
            String account = location.replaceAll(".*compte-", "");
            String page = TestAccounts.get(client, base, manager, "/bourse").body();
            assertTrue(page.contains("Trade Republic") && page.contains("CW8.PA"), page);

            // Nouvel achat du meme titre : quantite et cout s'ajoutent ; total paye
            TestAccounts.post(client, base, manager, "/bourse/ajouter", "symbole=CW8.PA&nom=ETF+Monde&quantity=1&montant=600&prix=total&compte=" + account);
            Matcher position = Pattern.compile("/bourse/ligne/(\\d+)/supprimer").matcher(TestAccounts.get(client, base, manager, "/bourse").body());
            assertTrue(position.find());
            String id = position.group(1);
            assertTrue(TestAccounts.get(client, base, manager, "/bourse").body().contains("value=\"3\""), "quantité cumulée");
            assertTrue(TestAccounts.get(client, base, manager, "/bourse").body().contains("value=\"533.33\""), "prix moyen : 1 600 / 3");

            // Modification : quantite et prix moyen
            assertEquals(303, TestAccounts.post(client, base, manager, "/bourse/ligne/" + id, "quantity=4&pru=510").statusCode());
            assertTrue(TestAccounts.get(client, base, manager, "/bourse").body().contains("value=\"510.00\""));

            // Import d'un releve vers un nouveau compte-titres : apercu, puis validation
            String preview = TestAccounts.post(client, base, manager, "/bourse/importer", "courtier=Revolut&compte=nouveau&compte_type=SECURITIES&compte_nom=Revolut&titulaire=1&texte="
                    + java.net.URLEncoder.encode("Date,Ticker,Type,Quantity,Price per share,Total Amount,Currency,FX Rate\n"
                    + "2026-01-02,AAPL,BUY - MARKET,2,USD 150,USD 300,USD,1.1\n", java.nio.charset.StandardCharsets.UTF_8)).body();
            assertTrue(preview.contains("AAPL") && preview.contains("Revolut"), preview);
            Matcher content = Pattern.compile("name=\"contenu\" value=\"([^\"]+)\"").matcher(preview);
            assertTrue(content.find());
            String imported = TestAccounts.location(TestAccounts.post(client, base, manager, "/bourse/importer/valider",
                    "contenu=" + content.group(1) + "&compte=nouveau&compte_type=SECURITIES&compte_nom=Revolut&titulaire=1&importer_1=on"));
            assertTrue(imported.startsWith("/bourse?ok=importe#compte-"), imported);
            assertTrue(TestAccounts.get(client, base, manager, "/bourse").body().contains("Compte-titres · Paul"));

            // Suppression
            assertEquals(303, TestAccounts.post(client, base, manager, "/bourse/ligne/" + id + "/supprimer", "").statusCode());
            assertTrue(!TestAccounts.get(client, base, manager, "/bourse").body().contains("CW8.PA"));
            assertEquals(404, TestAccounts.post(client, base, manager, "/bourse/ligne/999/supprimer", "").statusCode());
        }
    }
}
