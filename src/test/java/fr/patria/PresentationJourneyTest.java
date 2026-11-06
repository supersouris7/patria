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
            var manager = TestAccounts.account(client, base, admin, "gestion", "Gestion", 1, "access=on&manager=on");
            String users = TestAccounts.get(client, base, admin, "/administration/utilisateurs").body();
            before(users, "<h2>Utilisateurs", "<summary>Créer un compte de connexion");
            String households = TestAccounts.get(client, base, admin, "/administration/foyers").body();
            assertTrue(households.contains("<details><summary><b>"), "les details du foyer ne sont pas tous deployes");
            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/membres", "name=Paul&kind=PERSON").statusCode());
            assertEquals(303, TestAccounts.post(client, base, manager, "/administration/revenus",
                    "person=1&kind=SALARY&label=Salaire&amount=2500&frequency=MONTHLY&start=2026-01-01").statusCode());
            String home = TestAccounts.get(client, base, manager, "/").body();
            before(home, "class=\"metrics cols-4\"", "class=\"setup-progress\"");
            before(home, "class=\"metrics cols-4\"", "health-summary");
            String incomes = TestAccounts.get(client, base, manager, "/administration/revenus").body();
            before(incomes, "<h2>Revenus", "<summary>Ajouter un revenu");
            String expenses = TestAccounts.get(client, base, manager, "/administration/depenses").body();
            before(expenses, "<h2>Dépenses saisies", "<summary>Ajouter une dépense");
            before(expenses, "<summary>Ajouter une dépense", "<summary>Importer un fichier CSV");
            String guide = TestAccounts.get(client, base, manager, "/assistant").body();
            before(guide, "<h2>Les étapes", "<summary>Comment ça marche");
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
