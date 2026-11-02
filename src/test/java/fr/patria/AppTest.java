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

        String dashboard = get("/").body();
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
        assertEquals(303, post(admin + "/loyers", "csrf=" + csrf + "&monthly=500&start=2024-01-01&label=Locataire").statusCode());
        assertEquals(400, post(admin + "/loyers", "csrf=" + csrf + "&monthly=500&start=2024-05-01&end=2024-01-01").statusCode());

        assertTrue(get(admin + "/charges").body().contains("Estimée"), "années non saisies estimées");
        String page = get(property).body();
        assertTrue(page.contains("Enrichissement réel"));
        assertTrue(page.contains("Loyers perçus"));
        assertTrue(page.contains("500\u00a0€/mois"));
        assertTrue(page.contains("Rendement brut</dt><dd>6\u00a0%</dd>"), "500 × 12 / 100 000");
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
