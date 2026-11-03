package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Parcours complets a travers le vrai serveur HTTP. */
class AppTest {
    @TempDir
    Path directory;
    private App app;
    private HttpClient client;
    private String base;
    private String sessionCookie = "";

    @BeforeEach
    void start() {
        app = App.start(new Config("secret", directory.resolve("patria.sqlite3"), 0, TestData.CLOCK));
        base = "http://127.0.0.1:" + app.port();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        return sessionCookie.isEmpty() ? builder : builder.header("Cookie", sessionCookie);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return client.send(request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, String csrf, String name, String csv) throws Exception {
        String boundary = "patriaTestBoundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"csrf\"\r\n\r\n" + csrf + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n" + name + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"csv\"; filename=\"pret.csv\"\r\nContent-Type: text/csv\r\n\r\n" + csv + "\r\n"
                + "--" + boundary + "--\r\n";
        return client.send(request(path).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws Exception {
        HttpResponse<String> logged = post("/connexion", "password=secret");
        assertEquals(303, logged.statusCode());
        sessionCookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        HttpResponse<String> home = get("/");
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertTrue(csrf.find(), () -> home.statusCode() + " " + home.headers().map() + " " + home.body());
        return csrf.group(1);
    }

    @Test
    void privatePagesRequireLogin() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(303, response.statusCode());
        assertEquals("/connexion", response.headers().firstValue("Location").orElseThrow());
        assertEquals(200, get("/sante").statusCode());
        assertEquals("/connexion?erreur", post("/connexion", "password=faux").headers().firstValue("Location").orElseThrow());
    }

    @Test
    void formsWithoutValidCsrfTokenAreRejected() throws Exception {
        login();
        assertEquals(400, post("/bien", "csrf=faux&name=X&kind=HOUSE").statusCode());
    }

    @Test
    void fullJourneyFromPropertyToDashboard() throws Exception {
        String csrf = login();
        HttpResponse<String> created = post("/bien", "csrf=" + csrf + "&name=Maison&kind=HOUSE&purchase=250+000,00&purchase_date=2020-01-01"
                + "&estimate=300000&estimate_high=400000&estimate_date=2025-01-01&sale_fees_pct=8");
        assertEquals(303, created.statusCode());
        String propertyPath = created.headers().firstValue("Location").orElseThrow().replace("?ok=cree", "");

        String csv = "date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct\n"
                + "2026-01-01,DEBLOCAGE,200000,,,,200000,3\n2026-06-01,ECHEANCE,1000,500,500,,199500,3\n2026-07-01,ECHEANCE,1000,501,499,,198999,3\n";
        HttpResponse<String> loan = upload(propertyPath + "/prets", csrf, "Prêt principal", csv);
        assertEquals(303, loan.statusCode(), loan.body());

        assertEquals(204, post(propertyPath + "/valeur", "csrf=" + csrf + "&value=350000").statusCode());

        String dashboard = get("/immobilier").body();
        assertTrue(dashboard.contains("350 000 €"), "valeur retenue");
        assertTrue(dashboard.contains("Fourchette"));
        assertTrue(dashboard.contains("199 500 €"), "capital restant dû au 15/06/2026");
        assertTrue(get(propertyPath).body().contains("value=\"350000\""));

        HttpResponse<String> invalid = post(propertyPath.replace("/bien/", "/administration/bien/"), "csrf=" + csrf + "&name=Renommé&kind=HOUSE&estimate=300000&estimate_high=200000");
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("value=\"Renommé\""), "la saisie est conservée");
    }

    @Test
    void chargesAndRentsAreEnteredInAdministrationAndShownOnTheProperty() throws Exception {
        String csrf = login();
        String property = post("/bien", "csrf=" + csrf + "&name=Studio&kind=APARTMENT&purchase=100000&purchase_date=2024-01-01")
                .headers().firstValue("Location").orElseThrow().replace("?ok=cree", "");
        String admin = property.replace("/bien/", "/administration/bien/");

        assertEquals(303, post(admin + "/charges", "csrf=" + csrf + "&kind=PROPERTY_TAX&year=2025&amount=800").statusCode());
        assertEquals(400, post(admin + "/charges", "csrf=" + csrf + "&kind=PROPERTY_TAX&year=abc&amount=800").statusCode());
        assertEquals(303, post(admin + "/loyers", "csrf=" + csrf + "&kind=RENTAL&monthly=500&start=2024-01-01&label=Locataire").statusCode());
        assertEquals(400, post(admin + "/loyers", "csrf=" + csrf + "&kind=RENTAL&monthly=500&start=2024-05-01&end=2024-01-01").statusCode());
        assertEquals(400, post(admin + "/loyers", "csrf=" + csrf + "&kind=AUTRE&monthly=500&start=2024-01-01").statusCode());

        assertTrue(get(admin + "/charges").body().contains("Estimée"), "années non saisies estimées");
        String page = get(property).body();
        assertTrue(page.contains("Enrichissement réel"));
        assertTrue(page.contains("Loyers perçus"));
        assertTrue(page.contains("Loyer économisé"));
        assertTrue(page.contains("500\u00a0€/mois"));
        assertTrue(page.contains("Rendement brut</span></dt><dd>6\u00a0%</dd>"), "500 × 12 / 100 000");
    }

    @Test
    void principalResidenceAddsSavedRentToRealGain() throws Exception {
        String csrf = login();
        String property = post("/bien", "csrf=" + csrf + "&name=Maison&kind=HOUSE&purchase=300000&purchase_date=2024-06-15")
                .headers().firstValue("Location").orElseThrow().replace("?ok=cree", "");
        String admin = property.replace("/bien/", "/administration/bien/");
        assertEquals(303, post(admin + "/loyers", "csrf=" + csrf + "&kind=OWNER&monthly=1000&start=2024-06-15").statusCode());

        String page = get(property).body();
        assertTrue(page.contains("résidence principale"), "statut affiché sous le titre");
        // horloge de test au 15/06/2026 : 731 j × 12 000 / 365,25 ≈ 24 016 € économisés
        assertTrue(page.contains("+ 24\u202f016\u00a0€"), "loyer économisé dans le bilan");
    }

    @Test
    void propertyPageFlagsOldEstimateAndMissingOccupancy() throws Exception {
        String csrf = login();
        String property = post("/bien", "csrf=" + csrf + "&name=Ancien&kind=HOUSE&purchase=200000&purchase_date=2015-01-01"
                + "&estimate=250000&estimate_date=2020-01-01").headers().firstValue("Location").orElseThrow().replace("?ok=cree", "");

        String page = get(property).body();
        assertTrue(page.contains("il y a plus de 3 ans"));
        assertTrue(page.contains("Occupation non renseignée"));
        assertTrue(page.contains("Valeur estimée le 01/01/2020"));
        assertTrue(page.contains("non actualisée"), "sans commune, pas d'indice départemental");
    }

    @Test
    void householdBudgetIsFilledInAdministrationAndShownOnHome() throws Exception {
        String csrf = login();
        assertTrue(get("/").body().contains("Votre budget est vide"));

        assertEquals(303, post("/administration/foyer/personnes", "csrf=" + csrf + "&name=Camille").statusCode());
        String household = get("/administration/foyer").body();
        Matcher person = Pattern.compile("<option value=\"(\\d+)\"[^>]*>Camille</option>").matcher(household);
        assertTrue(person.find(), household);
        assertEquals(303, post("/administration/foyer/revenus", "csrf=" + csrf + "&person=" + person.group(1)
                + "&kind=SALARY&label=Salaire&amount=3000&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(400, post("/administration/foyer/revenus", "csrf=" + csrf + "&person=999&kind=SALARY&label=X&amount=1&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(303, post("/administration/depenses", "csrf=" + csrf + "&label=Mutuelle&category=HEALTH&amount=120&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(303, post("/administration/depenses", "csrf=" + csrf + "&label=Assurance+auto&category=INSURANCE&amount=600&frequency=ANNUAL&start=2026-08-01").statusCode());

        String home = get("/").body();
        assertTrue(home.contains("Reste à vivre"));
        assertTrue(home.contains("+2\u202f880\u00a0€"), "3 000 − 120 en juin");
        assertTrue(home.contains("Août 2026 : Assurance auto 600\u00a0€"), "alerte sur la dépense annuelle à venir");
        assertTrue(home.contains("<svg class=\"chart\""));
        String budget = get("/budget").body();
        assertTrue(budget.contains("Dépenses par catégorie"));
        assertTrue(budget.contains("<strong>170\u00a0€</strong>"), "dépenses moyennes : mutuelle 120 + assurance 600 / 12 = 170 € par mois");
        assertEquals(303, get("/administration").statusCode());
    }

    @Test
    void eachPersonHasOwnBudgetWithSharedExpenses() throws Exception {
        String csrf = login();
        post("/administration/foyer/personnes", "csrf=" + csrf + "&name=Alice");
        post("/administration/foyer/personnes", "csrf=" + csrf + "&name=Bob");
        Matcher ids = Pattern.compile("<option value=\"(\\d+)\"[^>]*>(Alice|Bob)</option>").matcher(get("/administration/foyer").body());
        java.util.Map<String, String> people = new java.util.HashMap<>();
        while (ids.find()) people.put(ids.group(2), ids.group(1));
        String alice = people.get("Alice");
        String bob = people.get("Bob");
        post("/administration/foyer/revenus", "csrf=" + csrf + "&person=" + alice + "&kind=SALARY&label=Salaire&amount=3000&frequency=MONTHLY&start=2026-01-01");

        // une répartition qui ne fait pas 100 % est refusée ; vide = parts égales
        assertEquals(400, post("/administration/depenses", "csrf=" + csrf + "&label=Loyer&category=HOUSING&amount=1000&frequency=MONTHLY&start=2026-01-01"
                + "&share_" + alice + "=60&share_" + bob + "=30").statusCode());
        assertEquals(303, post("/administration/depenses", "csrf=" + csrf + "&label=Loyer&category=HOUSING&amount=1000&frequency=MONTHLY&start=2026-01-01"
                + "&share_" + alice + "=60&share_" + bob + "=40").statusCode());
        assertEquals(303, post("/administration/depenses", "csrf=" + csrf + "&label=Internet&category=TELECOM&amount=40&frequency=MONTHLY&start=2026-01-01").statusCode());
        String expenses = get("/administration/depenses").body();
        assertTrue(expenses.contains("Alice 60\u00a0% · Bob 40\u00a0%"), expenses);
        assertTrue(expenses.contains("Parts égales"));

        String home = get("/").body();
        assertTrue(home.contains("Budget du foyer"));
        assertTrue(home.contains("class=\"scope-tabs\""));
        String aliceHome = get("/?personne=" + alice).body();
        assertTrue(aliceHome.contains("Budget d'Alice") || aliceHome.contains("Budget d&#39;Alice"), aliceHome);
        assertTrue(aliceHome.contains("+2\u202f380\u00a0€"), "3 000 − 600 de loyer − 20 d'internet");
        assertTrue(get("/budget?personne=" + bob).body().contains("Loyer (40\u00a0%)"));

        assertTrue(get("/administration/repartition").body().contains("Biens et prêts") || get("/administration/repartition").body().contains("Aucun bien"));

        // la personne choisie reste selectionnee entre Accueil et Budget
        assertTrue(aliceHome.contains("href=\"/budget?personne=" + alice + "\""), aliceHome);

        // une depense ouverte depuis le budget y ramene
        String budget = get("/budget?personne=" + bob).body();
        Matcher link = Pattern.compile("href=\"(/depense/(\\d+)\\?retour=%2Fbudget%3Fpersonne%3D" + bob + ")\"").matcher(budget);
        assertTrue(link.find(), budget);
        String edit = get(link.group(1)).body();
        assertTrue(edit.contains("href=\"/budget?personne=" + bob + "\""), edit);
        assertTrue(edit.contains("<title>Dépense · "), edit);
        HttpResponse<String> saved = post(link.group(1), "csrf=" + csrf + "&label=Loyer&category=HOUSING&amount=1000&frequency=MONTHLY&start=2026-01-01");
        assertEquals("/budget?personne=" + bob, saved.headers().firstValue("Location").orElseThrow());
        // une adresse de retour exterieure est ignoree
        HttpResponse<String> outside = post("/depense/" + link.group(2) + "?retour=%2F%2Fexemple.fr", "csrf=" + csrf
                + "&label=Loyer&category=HOUSING&amount=1000&frequency=MONTHLY&start=2026-01-01");
        assertEquals("/administration/depenses?ok=enregistre", outside.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void importsExpensesFromCsvAfterPreview() throws Exception {
        String csrf = login();
        post("/administration/foyer/personnes", "csrf=" + csrf + "&name=William");
        post("/administration/foyer/personnes", "csrf=" + csrf + "&name=Lou");
        Matcher ids = Pattern.compile("<option value=\"(\\d+)\"[^>]*>(William|Lou)</option>").matcher(get("/administration/foyer").body());
        java.util.Map<String, String> people = new java.util.HashMap<>();
        while (ids.find()) people.put(ids.group(2), ids.group(1));

        HttpResponse<String> template = get("/administration/depenses/modele.csv");
        assertEquals(200, template.statusCode());
        assertTrue(template.headers().firstValue("Content-Disposition").orElseThrow().contains("modele-depenses.csv"));
        assertTrue(template.body().contains("Mutuelle;Santé;35,69;Mensuel;01/01/2026;"));
        assertTrue(!template.body().contains("libelle"), "jamais d'en-tête");

        String csv = "Objectif Santé;Santé;35,69;Mensuel;01/01/2026;\nProtection juridique;Assurances;26,40;Annuel;01/12/2025;\nInternet;Télécom et abonnements;30;Mensuel;01/01/2026;\n";
        HttpResponse<String> preview = upload("/administration/depenses/import", csrf, "", csv);
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(preview.body().contains("3 dépenses à importer"), preview.body());
        assertTrue(preview.body().contains("name=\"attribution\""));
        assertTrue(preview.body().contains("name=\"attribution_3\""));
        assertTrue(!get("/administration/depenses").body().contains("Objectif Santé"), "rien n'est enregistré avant validation");

        // tout le fichier pour William, sauf Internet à parts égales
        Matcher content = Pattern.compile("name=\"contenu\" value=\"([^\"]+)\"").matcher(preview.body());
        assertTrue(content.find());
        HttpResponse<String> saved = post("/administration/depenses/import/valider", "csrf=" + csrf + "&contenu=" + content.group(1)
                + "&attribution=" + people.get("William") + "&attribution_3=egal");
        assertEquals("/administration/depenses?ok=importe", saved.headers().firstValue("Location").orElseThrow());
        String expenses = get("/administration/depenses").body();
        assertTrue(expenses.contains("William 100\u00a0%"), expenses);
        assertTrue(expenses.contains("Parts égales"));
        assertTrue(!get("/budget?personne=" + people.get("Lou")).body().contains("Objectif Santé"));

        // reimporter ne cree pas de doublon ; une ligne fausse bloque tout ; un en-tete est refuse
        assertTrue(upload("/administration/depenses/import", csrf, "", csv).body().contains("Toutes ces dépenses sont déjà saisies"));
        HttpResponse<String> wrong = upload("/administration/depenses/import", csrf, "", csv + "Gaz;Chauffage;50;Mensuel;01/01/2026;\n");
        assertEquals(400, wrong.statusCode());
        assertTrue(wrong.body().contains("Catégorie « Chauffage » inconnue."), wrong.body());
        assertTrue(!wrong.body().contains("name=\"contenu\""));
        assertTrue(upload("/administration/depenses/import", csrf, "", "libelle;categorie;montant;frequence;debut;fin\n" + csv).body()
                .contains("ressemble à un en-tête"));
    }

    @Test
    void transfersToJointAccountFromAdministration() throws Exception {
        String csrf = login();
        assertTrue(get("/administration/virements").body().contains("Un seul membre dans le foyer") || get("/administration/virements").body().contains("ajoutez un compte joint"));
        post("/administration/foyer/personnes", "csrf=" + csrf + "&name=William&kind=PERSON");
        post("/administration/foyer/personnes", "csrf=" + csrf + "&name=Compte+joint&kind=ACCOUNT");
        String foyer = get("/administration/foyer").body();
        assertTrue(foyer.contains("<small class=\"badge\">Compte joint</small>"), foyer);
        Matcher ids = Pattern.compile("<option value=\"(\\d+)\"[^>]*>(William|Compte joint)</option>").matcher(get("/administration/virements").body());
        java.util.Map<String, String> members = new java.util.HashMap<>();
        while (ids.find()) members.put(ids.group(2), ids.group(1));
        String william = members.get("William");
        String joint = members.get("Compte joint");

        assertEquals(400, post("/administration/virements", "csrf=" + csrf + "&from=" + william + "&to=" + william
                + "&amount=900&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(303, post("/administration/virements", "csrf=" + csrf + "&from=" + william + "&to=" + joint
                + "&label=&amount=900&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertTrue(get("/administration/virements").body().contains("William → Compte joint"));

        assertTrue(get("/budget?personne=" + william).body().contains("Virement vers Compte joint"));
        String account = get("/budget?personne=" + joint).body();
        assertTrue(account.contains("Virement de William"), account);
        assertTrue(account.contains("<p class=\"eyebrow\">Compte joint</p>"), account);
        assertTrue(!get("/budget").body().contains("Virement vers"), "neutre pour le foyer");
        assertTrue(get("/?personne=" + joint).body().contains("pas de taux pour un compte"));
    }

    @Test
    void loginReturnsToRequestedPage() throws Exception {
        HttpResponse<String> denied = get("/budget?personne=2");
        assertEquals("/connexion?suite=%2Fbudget%3Fpersonne%3D2", denied.headers().firstValue("Location").orElseThrow());
        String page = get("/connexion?suite=%2Fbudget%3Fpersonne%3D2").body();
        assertTrue(page.contains("name=\"suite\" value=\"/budget?personne=2\""), page);
        assertEquals("/connexion?erreur&suite=%2Fbudget%3Fpersonne%3D2",
                post("/connexion", "password=faux&suite=%2Fbudget%3Fpersonne%3D2").headers().firstValue("Location").orElseThrow());
        assertEquals("/budget?personne=2", post("/connexion", "password=secret&suite=%2Fbudget%3Fpersonne%3D2").headers().firstValue("Location").orElseThrow());
        assertEquals("/", post("/connexion", "password=secret&suite=%2F%2Fexemple.fr").headers().firstValue("Location").orElseThrow());
    }

    @Test
    void unknownPagesReturn404WithNavigation() throws Exception {
        login();
        HttpResponse<String> response = get("/bien/999");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Bien introuvable"));
        assertTrue(response.body().contains("<nav>"));
    }
}
