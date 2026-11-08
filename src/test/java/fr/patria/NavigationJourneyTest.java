package fr.patria;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NavigationJourneyTest {
    @TempDir
    Path directory;

    @Test
    void propertyMenuRespectsRightsHouseholdsAndHtmlEscaping() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, true))) {
            String base = "http://127.0.0.1:" + app.port();
            var admin = TestAccounts.admin(client, base);
            var manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            var reader = TestAccounts.account(client, base, admin, "lecture", "Lecture", 1, "access=on&whole=on&estate=on");
            var restricted = TestAccounts.account(client, base, admin, "budget", "Budget", 1, "access=on&whole=on");
            assertEquals(303, TestAccounts.post(client, base, manager, "/bien", "name=Maison+%3Cscript%3E&kind=HOUSE&purchase=250000&estimate=300000").statusCode());
            var links = TestAccounts.get(client, base, reader, "/navigation/biens");
            assertEquals(200, links.statusCode());
            assertTrue(links.body().contains("Maison &lt;script&gt;"));
            assertFalse(links.body().contains("<script>"));
            assertEquals(403, TestAccounts.get(client, base, restricted, "/navigation/biens").statusCode());
            assertEquals(403, TestAccounts.get(client, base, admin, "/navigation/biens").statusCode());
            String overview = TestAccounts.get(client, base, reader, "/immobilier").body();
            for (String anchor : new String[]{"biens", "prets", "taxe-fonciere"}) {
                assertTrue(TestAccounts.get(client, base, reader, "/immobilier/" + anchor).body().contains("id=\"" + anchor + "\""), anchor);
                assertFalse(overview.contains("id=\"" + anchor + "\""), "plus en double dans Patrimoine");
            }
            assertTrue(overview.indexOf("id=\"taxe-fonciere\"") < overview.indexOf("<h2>Bilan</h2>"), "bilan patrimonial en dernier");
            for (String tab : new String[]{"biens", "prets", "taxe-fonciere"}) {
                var page = TestAccounts.get(client, base, reader, "/immobilier/" + tab);
                assertEquals(200, page.statusCode());
                assertTrue(page.body().contains("aria-label=\"Navigation immobilière\""));
                assertEquals(403, TestAccounts.get(client, base, restricted, "/immobilier/" + tab).statusCode());
            }
            assertTrue(TestAccounts.get(client, base, reader, "/immobilier/biens").body().contains("data-filter"));
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/foyers", "name=Second").statusCode());
            assertEquals(303, TestAccounts.post(client, base, admin, "/administration/utilisateur/" + manager.id() + "/foyer/2", "access=on&all=on").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/foyer/choisir", "foyer=2").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/bien", "name=SECRET_FOYER_2&kind=HOUSE").statusCode());
            assertTrue(TestAccounts.get(client, base, manager, "/navigation/biens").body().contains("SECRET_FOYER_2"));
            assertFalse(TestAccounts.get(client, base, reader, "/navigation/biens").body().contains("SECRET_FOYER_2"));
        }
    }
}
