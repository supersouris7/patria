// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Historique : montants changes « a partir du », releve mensuel reconstitue, depenses reelles, revenu fiscal par annee. */
class HistoryTest {
    @TempDir
    Path directory;

    @Test
    void keepsAmountsOverTimeAndShowsTheEvolution() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, true))) {
            String base = "http://127.0.0.1:" + app.port();
            var admin = TestAccounts.admin(client, base);
            var manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/revenus",
                    "person=1&kind=SALARY&label=Salaire&amount=2000&frequency=MONTHLY&start=2024-01-01").statusCode());
            Matcher income = Pattern.compile("/revenu/(\\d+)").matcher(TestAccounts.get(client, base, manager, "/administration/revenus").body());
            assertTrue(income.find());
            String edit = TestAccounts.get(client, base, manager, "/revenu/" + income.group(1)).body();
            assertTrue(edit.contains("name=\"effet\""), "« à partir du » proposé");
            // Augmentation au 1er juin 2025 : l'ancien montant reste avant.
            assertEquals(303, TestAccounts.post(client, base, manager, "/revenu/" + income.group(1),
                    "person=1&kind=SALARY&label=Salaire&amount=2200&frequency=MONTHLY&start=2024-01-01&effet=2025-06-01").statusCode());
            String incomes = TestAccounts.get(client, base, manager, "/administration/revenus").body();
            assertTrue(incomes.matches("(?s).*2.000.*") && incomes.matches("(?s).*2.200.*"), "deux lignes : avant et après");
            // Correction sans date : la ligne est modifiee sur place.
            Matcher latest = Pattern.compile("/revenu/(\\d+)").matcher(incomes);
            assertTrue(latest.find(), "la ligne la plus récente en premier");
            String last = latest.group(1);
            assertEquals(303, TestAccounts.post(client, base, manager, "/revenu/" + last,
                    "person=1&kind=SALARY&label=Salaire&amount=2250&frequency=MONTHLY&start=2025-06-01&effet=").statusCode());

            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/depenses",
                    "label=Loyer&category=HOUSING&amount=800&frequency=MONTHLY&start=2024-01-01&share_1=100").statusCode());
            TestAccounts.get(client, base, manager, "/");
            String statement = "Date opération;Libellé;Débit;Crédit\n05/10/2026;PRLV EDF;60,00;\n12/10/2026;CB CARREFOUR;84,20;\n28/10/2026;VIR SALAIRE;;2250,00\n";
            TestAccounts.upload(client, base, manager, "/assistant/documents/importer", java.util.Map.of(), "csv", "releve.csv", statement);
            TestAccounts.upload(client, base, manager, "/assistant/documents/importer", java.util.Map.of(), "csv", "releve.csv", statement);
            assertEquals(303, TestAccounts.post(client, base, manager, "/epargne/fiscal", "rfr=26000&annee=2025&parts=1").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/epargne/fiscal", "rfr=27000&annee=2026&parts=1").statusCode());

            String bilan = TestAccounts.get(client, base, manager, "/bilan").body();
            assertTrue(bilan.contains("Votre évolution") && bilan.contains("Pouvoir d&#39;achat") || bilan.contains("Pouvoir d'achat"), "section du Bilan");
            assertTrue(bilan.contains("En euros courants"), "revenus en euros courants (indice des prix absent hors ligne)");
            assertTrue(bilan.contains("Patrimoine net") && bilan.contains("reconstitués"), "passé reconstitué");
            assertTrue(bilan.contains("Dépenses : prévu et réel"), "dépenses réelles des relevés");
            assertTrue(bilan.matches("(?s).*Réel <b>144.*"), "60 + 84,20, une seule fois malgré le double import");
            assertTrue(bilan.contains("Avis 2026") && bilan.contains("Avis 2025"), "revenu fiscal par année");
        }
    }
}
