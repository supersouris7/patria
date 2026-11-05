package fr.patria;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mode hors ligne (OFFLINE=true) : aucun appel externe, et la fiche du bien le dit. */
class OfflineTest {
    @TempDir
    Path directory;

    @Test
    void noExternalCallAndThePagesSayIt() throws Exception {
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, true))) {
            String base = "http://127.0.0.1:" + app.port();
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            TestAccounts.Visitor manager = TestAccounts.manager(client, base);
            String cookie = manager.cookie();
            client.send(HttpRequest.newBuilder(URI.create(base + "/bien")).header("Cookie", cookie).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("csrf=" + manager.csrf() + "&name=Studio&kind=APARTMENT&commune=Lyon&address=1+rue+de+la+Paix"
                            + "&purchase=100000&purchase_date=2020-01-01&estimate=120000&estimate_date=2025-01-01&sale_fees_pct=8")).build(), HttpResponse.BodyHandlers.ofString());
            String property = client.send(HttpRequest.newBuilder(URI.create(base + "/bien/1")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(property.contains("Mode hors ligne"), "pas de calcul du prix du marché, et c'est dit");
            String privacy = client.send(HttpRequest.newBuilder(URI.create(base + "/confidentialite")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(privacy.contains("Le mode hors ligne est actif"));
        }
    }
}
