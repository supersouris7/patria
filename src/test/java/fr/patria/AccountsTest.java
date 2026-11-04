package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Foyers, utilisateurs et droits, a travers le vrai serveur HTTP. */
class AccountsTest {
    @TempDir
    Path directory;
    private App app;
    private HttpClient client;
    private String base;

    /** Une personne connectee : son cookie de session et son jeton CSRF. */
    private record Visitor(String cookie, String csrf) {
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded");
        if (visitor != null) builder.header("Cookie", visitor.cookie());
        String body = visitor == null ? form : "csrf=" + visitor.csrf() + (form.isEmpty() ? "" : "&" + form);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Visitor login(String login, String password) throws Exception {
        HttpResponse<String> logged = post(null, "/connexion", "login=" + login + "&password=" + password);
        assertEquals(303, logged.statusCode());
        assertEquals("/", logged.headers().firstValue("Location").orElseThrow(), "connexion acceptée pour " + login);
        String cookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        HttpResponse<String> home = get(new Visitor(cookie, ""), "/compte");
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertTrue(csrf.find(), home.body());
        return new Visitor(cookie, csrf.group(1));
    }

    @Test
    void userSeesOnlyTheViewsOpenedByTheAdministrator() throws Exception {
        Visitor admin = login("admin", "secret");
        // Foyer principal : Paul (1), Camille (2), compte joint (3)
        assertEquals(303, post(admin, "/administration/foyer/personnes", "name=Paul&kind=PERSON").statusCode());
        assertEquals(303, post(admin, "/administration/foyer/personnes", "name=Camille&kind=PERSON").statusCode());
        assertEquals(303, post(admin, "/administration/foyer/personnes", "name=Compte+joint&kind=ACCOUNT").statusCode());
        String expense = "&category=OTHER&amount=10&frequency=MONTHLY&start=2026-01-01";
        assertEquals(303, post(admin, "/administration/depenses", "label=Club+de+Paul" + expense + "&share_1=100&share_2=0&share_3=0").statusCode());
        assertEquals(303, post(admin, "/administration/depenses", "label=Courses+communes" + expense).statusCode());
        assertEquals(303, post(admin, "/administration/depenses", "label=Yoga+de+Camille" + expense + "&share_1=0&share_2=100&share_3=0").statusCode());
        assertEquals(303, post(admin, "/administration/foyer/revenus", "person=2&kind=SALARY&label=Salaire+Camille&amount=2000&frequency=MONTHLY&start=2026-01-01").statusCode());

        HttpResponse<String> created = post(admin, "/administration/utilisateurs", "login=paul&name=Paul&password=motdepasse1");
        assertEquals("/administration/utilisateur/1?ok=utilisateur", created.headers().firstValue("Location").orElseThrow());
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=PAUL&name=Autre&password=motdepasse1").statusCode(), "identifiant déjà pris");
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=admin&name=X&password=motdepasse1").statusCode(), "identifiant de l'administrateur");
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=court&name=X&password=1234").statusCode(), "mot de passe trop court");
        assertEquals("/connexion?sans-foyer", post(null, "/connexion", "login=paul&password=motdepasse1").headers().firstValue("Location").orElseThrow(),
                "aucun foyer ouvert");
        assertEquals(400, post(admin, "/administration/utilisateur/1/foyer/1", "access=on").statusCode(), "au moins une vue");
        assertEquals(303, post(admin, "/administration/utilisateur/1/foyer/1", "access=on&member_1=on&member_3=on").statusCode());

        assertEquals("/connexion?erreur", post(null, "/connexion", "login=paul&password=mauvais1").headers().firstValue("Location").orElseThrow());
        Visitor paul = login("paul", "motdepasse1");

        String home = get(paul, "/").body();
        assertTrue(home.contains("Budget de Paul"), "vue de Paul par défaut, sans vue Foyer");
        assertTrue(home.contains("?personne=3"), "onglet du compte joint");
        assertFalse(home.contains("?personne=2"), "pas d'onglet Camille");
        assertFalse(home.contains(">Immobilier<"), "immobilier non ouvert");
        assertTrue(get(paul, "/?personne=2").body().contains("Budget de Paul"), "vue de Camille refusée : retour à Paul");
        assertFalse(get(paul, "/budget?personne=2").body().contains("Yoga de Camille"));

        for (String reserved : new String[]{"/immobilier", "/administration/biens", "/administration/repartition", "/administration/utilisateurs", "/administration/foyers"}) {
            assertEquals(403, get(paul, reserved).statusCode(), reserved);
        }
        assertEquals(403, post(paul, "/administration/foyer/personnes", "name=Intrus&kind=PERSON").statusCode());

        String expenses = get(paul, "/administration/depenses").body();
        assertTrue(expenses.contains("Club de Paul") && expenses.contains("href=\"/depense/1\""), "sa dépense, modifiable");
        assertTrue(expenses.contains("Courses communes") && !expenses.contains("href=\"/depense/2\""), "dépense partagée en lecture seule");
        assertFalse(expenses.contains("Yoga de Camille"), "dépense de Camille invisible");
        assertFalse(expenses.contains("share_2"), "pas de part de Camille dans le formulaire");
        assertEquals(403, get(paul, "/depense/2").statusCode());
        assertEquals(403, post(paul, "/depense/2", "label=Pirate" + expense).statusCode());
        assertEquals(404, get(paul, "/depense/3").statusCode());
        assertEquals(303, post(paul, "/depense/1", "label=Club+de+tennis" + expense).statusCode());
        assertEquals(303, post(paul, "/administration/depenses", "label=Essence" + expense).statusCode());
        assertTrue(get(admin, "/administration/depenses").body().contains("Paul 100"), "« parts égales » d'un utilisateur : entre ses membres seulement");

        String incomes = get(paul, "/administration/foyer").body();
        assertFalse(incomes.contains("Salaire Camille"), "revenus de Camille invisibles");
        assertFalse(incomes.contains("Membres du foyer"), "pas de gestion des membres");
        assertEquals(404, get(paul, "/revenu/1").statusCode());
        assertEquals(400, post(paul, "/administration/foyer/revenus", "person=2&kind=SALARY&label=X&amount=1&frequency=MONTHLY&start=2026-01-01").statusCode(),
                "revenu pour Camille refusé");
        assertEquals(303, post(paul, "/administration/virements", "from=1&to=3&amount=300&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(400, post(paul, "/administration/virements", "from=2&to=3&amount=300&frequency=MONTHLY&start=2026-01-01").statusCode());

        // Mot de passe
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=faux&password=nouveau123&confirm=nouveau123").statusCode());
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=motdepasse1&password=nouveau123&confirm=autre1234").statusCode());
        assertEquals(303, post(paul, "/compte/mot-de-passe", "current=motdepasse1&password=nouveau123&confirm=nouveau123").statusCode());
        login("paul", "nouveau123");

        // Desactivation : la session tombe aussitot
        assertEquals(303, post(admin, "/administration/utilisateur/1", "name=Paul").statusCode());
        assertEquals(303, get(paul, "/").statusCode(), "compte désactivé : retour à la connexion");
        assertEquals("/connexion?erreur", post(null, "/connexion", "login=paul&password=nouveau123").headers().firstValue("Location").orElseThrow());
    }

    @Test
    void householdsAreSeparateAndSelectable() throws Exception {
        Visitor admin = login("admin", "secret");
        assertEquals(303, post(admin, "/administration/foyer/personnes", "name=Paul&kind=PERSON").statusCode());
        assertEquals("/administration/foyers?ok=foyer", post(admin, "/administration/foyers", "name=Foyer+Martin").headers().firstValue("Location").orElseThrow());
        assertTrue(Files.exists(directory.resolve("foyer-2.sqlite3")), "une base par foyer");
        assertTrue(Files.exists(directory.resolve(App.ACCOUNTS_FILE)));

        assertTrue(get(admin, "/").body().contains("name=\"foyer\""), "sélecteur de foyer à partir de deux foyers");
        assertEquals(303, post(admin, "/foyer/choisir", "foyer=2").statusCode());
        assertFalse(get(admin, "/administration/foyer").body().contains("Paul"), "foyer Martin : ses propres membres");
        assertEquals(303, post(admin, "/administration/foyer/personnes", "name=Martine&kind=PERSON").statusCode());
        assertEquals(303, post(admin, "/foyer/choisir", "foyer=1").statusCode());
        String first = get(admin, "/administration/foyer").body();
        assertTrue(first.contains("Paul") && !first.contains("Martine"));

        assertEquals(303, post(admin, "/administration/utilisateurs", "login=martine&name=Martine&password=motdepasse1").statusCode());
        assertEquals(303, post(admin, "/administration/utilisateur/1/foyer/2", "access=on&whole=on&estate=on").statusCode());
        Visitor martine = login("martine", "motdepasse1");
        assertFalse(get(martine, "/").body().contains("name=\"foyer\""), "un seul foyer : pas de sélecteur");
        assertEquals(200, get(martine, "/immobilier").statusCode(), "immobilier ouvert");
        assertEquals(403, post(martine, "/foyer/choisir", "foyer=1").statusCode(), "foyer non ouvert");

        assertEquals(400, post(admin, "/administration/foyers/1/supprimer", "").statusCode(), "le foyer principal reste");
        assertEquals(303, post(admin, "/administration/foyers/2/supprimer", "").statusCode());
        assertFalse(Files.exists(directory.resolve("foyer-2.sqlite3")), "base mise de côté, renommée");
        assertEquals(303, get(martine, "/").statusCode(), "plus aucun foyer : déconnectée");
    }
}
