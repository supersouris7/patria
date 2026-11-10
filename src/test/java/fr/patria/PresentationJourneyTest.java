// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresentationJourneyTest {
    @TempDir
    Path directory;

    @Test
    void consultationComesBeforeCreationAndSecondaryHelp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (App app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK, true))) {
            String base = "http://127.0.0.1:" + app.port();
            var admin = TestAccounts.admin(client, base);
            var manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&all=on");
            String users = TestAccounts.get(client, base, admin, "/administration/utilisateurs").body();
            before(users, "<summary>Inscriptions", "<h2>Comptes");
            String households = TestAccounts.get(client, base, admin, "/administration/foyers").body();
            assertTrue(households.contains("class=\"data admin-list\""), "les foyers en tableau, la fiche s'ouvre à part");
            assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/revenus",
                    "person=1&kind=SALARY&label=Salaire&amount=2500&frequency=MONTHLY&start=2026-01-01").statusCode());
            String home = TestAccounts.get(client, base, manager, "/").body();
            before(home, "class=\"metrics cols-4\"", "class=\"setup-progress\"");
            before(home, "class=\"metrics cols-4\"", "health-summary");
            String incomes = TestAccounts.get(client, base, manager, "/administration/revenus").body();
            before(incomes, "<summary>Ajouter un revenu", "<h2>Revenus");
            String expenses = TestAccounts.get(client, base, manager, "/administration/depenses").body();
            before(expenses, "<summary>Ajouter une dépense", "<summary>Importer (réponse d");
            before(expenses, "<summary>Importer (réponse d", "<h2>Dépenses saisies");
            String guide = TestAccounts.get(client, base, manager, "/assistant").body();
            before(guide, "<summary>Comment ça marche", "<h2>Les étapes");
            String budget = TestAccounts.get(client, base, manager, "/budget").body();
            before(budget, "<h2>Dépenses par catégorie", "<summary>Détail du taux d&#39;endettement");
            String error = TestAccounts.post(client, base, manager, "/administration/revenus",
                    "person=1&kind=SALARY&label=Salaire&amount=invalide&frequency=MONTHLY&start=2026-01-01").body();
            assertTrue(error.contains("<details open><summary>Ajouter un revenu"), "formulaire ouvert apres une erreur");
        }
    }

    private static void before(String html, String first, String second) {
        assertTrue(html.contains(first) && html.contains(second) && html.indexOf(first) < html.indexOf(second), first + " avant " + second);
    }
}
