package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Assistant d'import, a travers le vrai serveur HTTP. */
class WizardTest {
    @TempDir
    Path directory;
    private App app;
    private HttpClient client;
    private String base;

    private record Visitor(String cookie, String csrf, long id) {
        Visitor(String cookie, String csrf) {
            this(cookie, csrf, 0);
        }
    }

    /** L'administrateur : il cree les comptes (il ne voit pas les finances). */
    private TestAccounts.Visitor root;

    /** Le gestionnaire du foyer principal, qui saisit (le premier compte cree). */
    private Visitor manager() throws Exception {
        root = TestAccounts.admin(client, base);
        TestAccounts.Visitor manager = TestAccounts.account(client, base, root, "gestion", "Gestion", 1, "access=on&manager=on");
        return new Visitor(manager.cookie(), manager.csrf(), manager.id());
    }

    /** Un compte lie a un membre ({@code membre=1%3A1}), mot de passe provisoire deja change. */
    private Visitor user(String login, String name, String member) throws Exception {
        TestAccounts.Visitor user = TestAccounts.account(client, base, root, login, name, "membre=" + member, 1, null);
        return new Visitor(user.cookie(), user.csrf(), user.id());
    }

    @BeforeEach
    void start() {
        app = App.start(new Config("secret", "admin", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK));
        base = "http://127.0.0.1:" + app.port();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private HttpResponse<String> get(Visitor visitor, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", visitor.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        String body = visitor == null ? form : "csrf=" + visitor.csrf() + (form.isEmpty() ? "" : "&" + form);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(Visitor visitor, String path, String name, String csv) throws Exception {
        String boundary = "patriaWizardBoundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n" + visitor.csrf() + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"csv\"; filename=\"pret.csv\"\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n"
                + "--" + boundary + "--\r\n";
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", visitor.cookie())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Visitor login(String login, String password) throws Exception {
        HttpResponse<String> logged = post(null, "/connexion", "login=" + login + "&password=" + password);
        String cookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(get(new Visitor(cookie, ""), "/compte").body());
        assertTrue(csrf.find());
        return new Visitor(cookie, csrf.group(1));
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElseThrow();
    }

    @Test
    void managerFillsTheHouseholdStepByStep() throws Exception {
        Visitor admin = manager();
        String home = get(admin, "/assistant").body();
        assertTrue(home.contains("Commencer") && home.contains("Tout revoir"));

        // 1. Documents : coche manuelle, puis coche automatique
        assertTrue(get(admin, "/assistant/documents").body().contains("Les 3 derniers bulletins de salaire"));
        assertEquals(303, post(admin, "/assistant/documents/coche", "document=statements&status=HAVE").statusCode());
        assertTrue(get(admin, "/assistant/documents").body().contains("class=\"doc have\" id=\"doc-statements\""), "coche verte manuelle");
        assertEquals("/assistant/membres", location(post(admin, "/assistant/documents/terminer", "")), "étape suivante");

        // 2. Membres, 3. Revenus
        assertEquals(303, post(admin, "/assistant/membres/ajouter", "name=Paul&kind=PERSON").statusCode());
        assertEquals(303, post(admin, "/assistant/membres/ajouter", "name=Camille&kind=PERSON").statusCode());
        assertEquals(303, post(admin, "/assistant/membres/ajouter", "name=Compte+joint&kind=ACCOUNT").statusCode());
        assertEquals(303, post(admin, "/assistant/revenus/ajouter", "person=1&kind=SALARY&label=Salaire&amount=2500&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(400, post(admin, "/assistant/revenus/ajouter", "person=3&kind=SALARY&label=X&amount=1&frequency=MONTHLY&start=2026-01-01").statusCode(),
                "pas de revenu pour le compte joint ici : seulement les personnes ouvertes");
        assertTrue(get(admin, "/assistant/documents").body().contains("class=\"doc have\" id=\"doc-payslips\""), "coche verte automatique : salaire saisi");

        // 8. Depenses fixes : listes a cocher
        HttpResponse<String> missing = post(admin, "/assistant/depenses/ajouter", "on_spotify=on&amount_netflix=13,49&frequency_netflix=MONTHLY&start_netflix=2026-01-01");
        assertEquals(400, missing.statusCode());
        assertTrue(missing.body().contains("Rien n&#39;est enregistré") || missing.body().contains("Rien n'est enregistré"), "tout ou rien");
        assertEquals(303, post(admin, "/assistant/depenses/ajouter", "amount_netflix=13,49&frequency_netflix=MONTHLY&start_netflix=2026-01-01"
                + "&on_creche=on&amount_creche=420&frequency_creche=MONTHLY&who_creche=2&label_custom1=Cours+de+piano&category_custom1=LEISURE&amount_custom1=60"
                + "&frequency_custom1=MONTHLY").statusCode());
        String fixed = get(admin, "/assistant/depenses").body();
        assertTrue(fixed.contains("Déjà saisie : 13,49"), "ligne saisie affichée cochée, non reproposée");
        assertTrue(get(admin, "/administration/depenses").body().contains("Cours de piano"), "ligne libre enregistrée");
        assertTrue(get(admin, "/administration/depenses").body().contains("Camille 100"), "« Pour qui » : Camille");

        // 9. Impots, 10. Virements
        assertEquals(303, post(admin, "/assistant/impots/ajouter", "amount_impot-revenu=210&frequency_impot-revenu=MONTHLY&who_impot-revenu=1").statusCode());
        assertEquals(303, post(admin, "/assistant/virements/ajouter", "from=1&to=3&amount=600&frequency=MONTHLY&start=2026-01-01").statusCode());

        // 5-7. Biens, pret, charges et occupation
        assertEquals(303, post(admin, "/assistant/biens/ajouter", "name=Studio&kind=APARTMENT&commune=Lyon&purchase=150000&purchase_date=2020-01-01"
                + "&estimate=170000&estimate_date=2025-01-01&sale_fees_pct=8").statusCode());
        String csv = "date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct\n2020-01-01,DEBLOCAGE,120000,,,,120000,1.5\n"
                + "2020-02-01,ECHEANCE,600,450,150,,119550,1.5\n";
        assertEquals("/assistant/prets?ok=enregistre#bien-1", location(upload(admin, "/assistant/prets/1", "Prêt principal", csv)));
        assertEquals(303, post(admin, "/assistant/charges/1", "kind=PROPERTY_TAX&year=2026&amount=900").statusCode());
        assertEquals(303, post(admin, "/assistant/occupation/1", "kind=RENTAL&monthly=650&start=2020-02-01").statusCode());
        String documents = get(admin, "/assistant/documents").body();
        assertTrue(documents.contains("class=\"doc have\" id=\"doc-amortization\"") && documents.contains("class=\"doc have\" id=\"doc-property-tax\""));

        // Progression : passer, reprendre, revue
        assertEquals("/assistant/revenus", location(post(admin, "/assistant/membres/passer", "")), "étape passée, suivante à faire");
        assertTrue(get(admin, "/assistant").body().contains("href=\"/assistant/revenus\""), "Reprendre : la première étape à faire");
        assertEquals("/assistant/membres?mode=revue", location(post(admin, "/assistant/documents/terminer", "mode=revue")), "revue : l'étape suivante, même faite");
        String summary = get(admin, "/assistant/recapitulatif").body();
        assertTrue(summary.contains("Passé") && summary.contains("Fait") && summary.contains("À faire"));
        assertEquals(303, post(admin, "/assistant/recommencer", "").statusCode());
        assertTrue(get(admin, "/assistant").body().contains("Commencer"), "progression effacée");
        assertTrue(get(admin, "/administration/depenses").body().contains("Netflix"), "les données restent");
    }

    @Test
    void possibleErrorsAreCaught() throws Exception {
        Visitor admin = manager();
        post(admin, "/assistant/membres/ajouter", "name=Paul&kind=PERSON");
        post(admin, "/assistant/membres/ajouter", "name=Camille&kind=PERSON");

        // Double envoi (retour arriere, deux onglets) : pas de doublon
        String netflix = "amount_netflix=13,49&frequency_netflix=MONTHLY&start_netflix=2026-01-01";
        assertEquals(303, post(admin, "/assistant/depenses/ajouter", netflix).statusCode());
        assertEquals(303, post(admin, "/assistant/depenses/ajouter", netflix).statusCode());
        String expenses = get(admin, "/administration/depenses").body();
        assertEquals(expenses.indexOf(">Netflix<"), expenses.lastIndexOf(">Netflix<"), "une seule fois");

        // Impot a la source : une ligne par personne reste possible
        assertEquals(303, post(admin, "/assistant/impots/ajouter", "amount_impot-revenu=210&frequency_impot-revenu=MONTHLY&who_impot-revenu=1").statusCode());
        assertTrue(get(admin, "/assistant/impots").body().contains("name=\"amount_impot-revenu\""), "ligne toujours proposée pour la deuxième personne");
        assertEquals(303, post(admin, "/assistant/impots/ajouter", "amount_impot-revenu=180&frequency_impot-revenu=MONTHLY&who_impot-revenu=2").statusCode());
        assertTrue(get(admin, "/assistant/impots").body().contains("Camille 100"), "les deux saisies, chacune avec sa personne");

        // Occupation : pas de chevauchement (le loyer compterait deux fois)
        post(admin, "/assistant/biens/ajouter", "name=Studio&kind=APARTMENT&purchase=150000&purchase_date=2020-01-01&estimate=170000&estimate_date=2025-01-01&sale_fees_pct=8");
        assertEquals(303, post(admin, "/assistant/occupation/1", "kind=RENTAL&monthly=650&start=2020-02-01").statusCode());
        HttpResponse<String> overlap = post(admin, "/assistant/occupation/1", "kind=RENTAL&monthly=700&start=2024-01-01");
        assertEquals(400, overlap.statusCode());
        assertTrue(overlap.body().contains("chevauche"));
        assertEquals(400, post(admin, "/administration/bien/1/loyers", "kind=OWNER&monthly=900&start=2023-01-01").statusCode(), "même règle dans l'administration");

        // Erreurs de saisie : message clair, rien d'enregistre
        assertEquals(400, post(admin, "/assistant/charges/1", "kind=PROPERTY_TAX&year=1800&amount=900").statusCode(), "année invalide");
        assertEquals(400, post(admin, "/assistant/depenses/ajouter", "label_custom1=&amount_custom1=50&category_custom1=OTHER&frequency_custom1=MONTHLY").statusCode(),
                "ligne libre sans libellé");
        assertEquals(400, post(admin, "/assistant/depenses/ajouter", "amount_gaz=abc&frequency_gaz=MONTHLY").statusCode(), "montant illisible");
        assertEquals(404, post(admin, "/assistant/charges/99", "kind=PROPERTY_TAX&year=2026&amount=900").statusCode(), "bien inconnu");
        assertEquals(400, upload(admin, "/assistant/prets/1", "Prêt", "pas,un,echeancier\n").statusCode(), "échéancier illisible");

        // Utilisateur supprime : un nouveau compte avec le meme numero repart de zero
        Visitor paul = user("paul", "Paul", "1%3A1");
        post(paul, "/assistant/documents/terminer", "");
        assertEquals(303, TestAccounts.post(client, base, root, "/administration/utilisateur/" + paul.id() + "/supprimer", "").statusCode());
        assertTrue(get(user("jules", "Jules", "1%3A2"), "/assistant").body().contains("Commencer"), "pas de progression héritée");
    }

    @Test
    void annualReviewChangesAmountsFromADate() throws Exception {
        Visitor admin = manager();
        post(admin, "/assistant/membres/ajouter", "name=Paul&kind=PERSON");
        post(admin, "/assistant/membres/ajouter", "name=Camille&kind=PERSON");
        post(admin, "/assistant/revenus/ajouter", "person=1&kind=SALARY&label=Salaire&amount=2500&frequency=MONTHLY&start=2025-01-01");
        post(admin, "/assistant/depenses/ajouter", "amount_netflix=13,49&frequency_netflix=MONTHLY&start_netflix=2025-01-01&who_netflix=2"
                + "&amount_spotify=10,99&frequency_spotify=MONTHLY&start_spotify=2025-01-01");
        String review = get(admin, "/assistant/revue-annuelle").body();
        assertTrue(review.contains("Netflix") && review.contains("Salaire") && review.contains("Calendrier des mises à jour"));

        assertEquals(303, post(admin, "/assistant/revue-annuelle/depense/1", "action=update&amount=15,99&from=2026-12-01").statusCode());
        String expenses = get(admin, "/administration/depenses").body();
        assertTrue(expenses.contains("13,49") && expenses.contains("15,99"), "l'ancien montant jusqu'à la veille, le nouveau ensuite");
        assertTrue(expenses.lastIndexOf("Camille 100") > expenses.indexOf("Camille 100"), "même répartition pour le nouveau montant");
        assertEquals(303, post(admin, "/assistant/revue-annuelle/depense/2", "action=stop&from=2026-12-01").statusCode());
        assertTrue(get(admin, "/administration/depenses").body().contains("30/11/2026"), "abonnement arrêté la veille");
        assertEquals(303, post(admin, "/assistant/revue-annuelle/revenu/1", "action=update&amount=2700&from=2027-01-01").statusCode());
        assertTrue(get(admin, "/administration/revenus").body().contains("700,00"), "augmentation enregistrée");
        assertEquals(400, post(admin, "/assistant/revue-annuelle/depense/3", "action=update&amount=abc&from=2026-12-01").statusCode());

        assertTrue(get(admin, "/").body().contains("Revue annuelle conseillée"), "données de plus d'un an, jamais revues");
        assertEquals(303, post(admin, "/assistant/revue-annuelle/terminer", "").statusCode());
        assertFalse(get(admin, "/").body().contains("Revue annuelle conseillée"), "revue faite : plus de rappel");
        assertTrue(get(admin, "/assistant/revue-annuelle").body().contains("Dernière revue terminée le"));
    }

    @Test
    void userHasOnlyTheStepsOfItsOwnLines() throws Exception {
        Visitor admin = manager();
        post(admin, "/administration/membres", "name=Paul&kind=PERSON");
        post(admin, "/administration/membres", "name=Camille&kind=PERSON");
        Visitor paul = user("paul", "Paul", "1%3A1");

        String home = get(paul, "/assistant").body();
        assertFalse(home.contains("/assistant/membres") || home.contains("/assistant/biens"), "pas d'étapes réservées");
        assertEquals(403, get(paul, "/assistant/membres").statusCode());
        assertEquals(403, post(paul, "/assistant/biens/ajouter", "name=X&kind=HOUSE").statusCode());
        assertFalse(get(paul, "/assistant/documents").body().contains("doc-deed"), "documents de l'immobilier masqués");

        assertEquals(400, post(paul, "/assistant/revenus/ajouter", "person=2&kind=SALARY&label=X&amount=1&frequency=MONTHLY&start=2026-01-01").statusCode(),
                "revenu de Camille refusé");
        assertEquals(303, post(paul, "/assistant/depenses/ajouter", "amount_spotify=10,99&frequency_spotify=MONTHLY").statusCode());
        assertTrue(get(admin, "/administration/depenses").body().contains("Paul 100"), "« parts égales » d'un utilisateur : ses membres seulement");

        post(paul, "/assistant/documents/terminer", "");
        assertTrue(get(admin, "/assistant").body().contains("Commencer"), "progression propre à chaque personne");
    }
}
