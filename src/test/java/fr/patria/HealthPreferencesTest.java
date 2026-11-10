// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria;

import static org.junit.jupiter.api.Assertions.*;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthPreferencesTest {
    @TempDir
    Path directory;

    @Test
    void ignoreRestoreRespectsViewsAndChangesTheSummaryWithoutHidingPdfDisclosures() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, true))) {
            String base = "http://127.0.0.1:" + app.port();
            var admin = TestAccounts.admin(client, base);
            var manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/revenus",
                    "person=1&kind=SALARY&label=Salaire&amount=2500&frequency=MONTHLY&start=2026-01-01").statusCode());
            var reader = TestAccounts.account(client, base, admin, "lecture", "Lecture", 1, "access=on&whole=on&member_1=on");
            assertTrue(TestAccounts.get(client, base, manager, "/").body().contains("health-summary"));
            assertTrue(TestAccounts.get(client, base, manager, "/navigation/etat").body().contains("\"warnings\":1"));
            assertEquals(403, TestAccounts.get(client, base, admin, "/navigation/etat").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/bilan/ignorer", "check=withholding%3AWARN").statusCode());
            String ignored = TestAccounts.get(client, base, manager, "/bilan").body();
            assertFalse(ignored.substring(0, ignored.indexOf("id=\"ignores\"")).contains("Prélèvement à la source absent"));
            assertTrue(ignored.contains("Points ignorés (1)") && ignored.contains("Rétablir"));
            assertFalse(TestAccounts.get(client, base, manager, "/").body().contains("health-summary"));
            assertTrue(TestAccounts.get(client, base, manager, "/navigation/etat").body().contains("\"warnings\":0"));
            assertTrue(TestAccounts.get(client, base, reader, "/navigation/etat").body().contains("\"warnings\":1"));
            assertEquals(303, TestAccounts.post(client, base, manager, "/assistant/documents/passer", "").statusCode());
            assertTrue(TestAccounts.get(client, base, manager, "/navigation/etat").body().contains("\"progress\":8"), "une etape sur douze");
            assertTrue(TestAccounts.get(client, base, reader, "/").body().contains("health-summary"), "autre compte non affecté");
            assertEquals(400, TestAccounts.post(client, base, reader, "/bilan/retablir", "check=withholding%3AWARN").statusCode());
            assertEquals(400, TestAccounts.post(client, base, manager, "/bilan/ignorer", "check=property-999-occupancy%3AWARN").statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + "/bilan/retablir")).header("Cookie", manager.cookie())
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("check=withholding%3AWARN")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "CSRF obligatoire");
            byte[] pdf = client.send(HttpRequest.newBuilder(URI.create(base + "/bilan.pdf")).header("Cookie", manager.cookie()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            try (PdfReader document = new PdfReader(pdf)) {
                PdfTextExtractor extractor = new PdfTextExtractor(document);
                StringBuilder text = new StringBuilder();
                for (int page = 1; page <= document.getNumberOfPages(); page++) text.append(extractor.getTextFromPage(page));
                assertTrue(text.toString().contains("Points ignorés") && text.toString().contains("Prélèvement à la source absent"));
            }
            assertEquals(303, TestAccounts.post(client, base, manager, "/bilan/retablir", "check=withholding%3AWARN").statusCode());
            assertTrue(TestAccounts.get(client, base, manager, "/").body().contains("health-summary"));
            String account = TestAccounts.get(client, base, manager, "/compte").body();
            assertFalse(account.contains("Revoir l'accueil") || account.contains("class=\"last-login"));
            assertTrue(account.contains("id=\"connexions\""), "les connexions de sécurité restent consultables");
            assertTrue(account.contains("aria-label=\"Bilan de santé\"") && account.contains("aria-label=\"Assistant\""));
        }
    }
}
