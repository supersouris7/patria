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

    private record Visitor(String cookie, String csrf, long id) {
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

    private static TestAccounts.Visitor of(Visitor visitor) {
        return visitor == null ? null : new TestAccounts.Visitor(visitor.cookie(), visitor.csrf(), visitor.id());
    }

    private static Visitor of(TestAccounts.Visitor visitor) {
        return new Visitor(visitor.cookie(), visitor.csrf(), visitor.id());
    }

    private HttpResponse<String> get(Visitor visitor, String path) throws Exception {
        return TestAccounts.get(client, base, of(visitor), path);
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        return TestAccounts.post(client, base, of(visitor), path, form);
    }

    private Visitor admin() throws Exception {
        return of(TestAccounts.admin(client, base));
    }

    /** Compte cree par l'administrateur, mot de passe provisoire change ({@code login-motdepasse}). */
    private Visitor account(Visitor admin, String login, String name, String extra, long household, String access) throws Exception {
        return of(TestAccounts.account(client, base, of(admin), login, name, extra, household, access));
    }

    private Visitor login(String login, String password) throws Exception {
        return of(TestAccounts.login(client, base, login, password));
    }

    @Test
    void userSeesOnlyTheViewsOpenedByTheAdministrator() throws Exception {
        Visitor admin = admin();
        Visitor manager = account(admin, "gestion", "Gestion", "", 1, "access=on&manager=on");
        // Foyer principal : Paul (1), Camille (2), compte joint (3), saisis par le gestionnaire
        assertEquals(303, post(manager, "/administration/membres", "name=Paul&kind=PERSON").statusCode());
        assertEquals(303, post(manager, "/administration/membres", "name=Camille&kind=PERSON").statusCode());
        assertEquals(303, post(manager, "/administration/membres", "name=Compte+joint&kind=ACCOUNT").statusCode());
        String expense = "&category=OTHER&amount=10&frequency=MONTHLY&start=2026-01-01";
        assertEquals(303, post(manager, "/administration/depenses", "label=Club+de+Paul" + expense + "&share_1=100&share_2=0&share_3=0").statusCode());
        assertEquals(303, post(manager, "/administration/depenses", "label=Courses+communes" + expense).statusCode());
        assertEquals(303, post(manager, "/administration/depenses", "label=Yoga+de+Camille" + expense + "&share_1=0&share_2=100&share_3=0").statusCode());
        assertEquals(303, post(manager, "/administration/revenus", "person=2&kind=SALARY&label=Salaire+Camille&amount=2000&frequency=MONTHLY&start=2026-01-01").statusCode());

        // L'administrateur ne voit aucune finance
        assertEquals("/administration/foyers", get(admin, "/").headers().firstValue("Location").orElseThrow(), "son accueil : les foyers");
        for (String finance : new String[]{"/budget", "/administration/depenses", "/administration/revenus", "/immobilier", "/bilan", "/assistant", "/previsionnel.pdf"}) {
            assertEquals(403, get(admin, finance).statusCode(), finance);
        }
        assertEquals(403, post(admin, "/administration/depenses", "label=Intrus" + expense).statusCode());
        String households = get(admin, "/administration/foyers").body();
        assertTrue(households.contains("Camille") && !households.contains("Yoga") && !households.contains("2 000"), "les membres, pas les montants");
        assertTrue(households.contains("Avancement de la configuration") && households.contains("gestionnaire"));

        HttpResponse<String> created = post(admin, "/administration/utilisateurs", "login=paul&name=Paul&password=motdepasse1");
        assertEquals("/administration/utilisateur/2?ok=utilisateur", created.headers().firstValue("Location").orElseThrow());
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=PAUL&name=Autre&password=motdepasse1").statusCode(), "identifiant déjà pris");
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=admin&name=X&password=motdepasse1").statusCode(), "identifiant de l'administrateur");
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=court&name=X&password=1234").statusCode(), "mot de passe trop court");
        assertEquals("/connexion?sans-foyer", post(null, "/connexion", "login=paul&password=motdepasse1").headers().firstValue("Location").orElseThrow(),
                "aucun foyer ouvert");
        assertEquals(400, post(admin, "/administration/utilisateur/2/foyer/1", "access=on").statusCode(), "au moins une vue");
        assertEquals(303, post(admin, "/administration/utilisateur/2/foyer/1", "access=on&member_1=on&member_3=on").statusCode());
        assertEquals(403, post(manager, "/administration/utilisateur/2/foyer/1", "access=on&manager=on").statusCode(), "seul l'administrateur règle les droits");

        // Mot de passe provisoire : a changer avant toute autre page
        assertEquals("/connexion?erreur", post(null, "/connexion", "login=paul&password=mauvais1").headers().firstValue("Location").orElseThrow());
        HttpResponse<String> first = post(null, "/connexion", "login=paul&password=motdepasse1");
        assertEquals("/compte/nouveau-mot-de-passe", first.headers().firstValue("Location").orElseThrow());
        Visitor paul = login("paul", "motdepasse1");
        assertEquals("/compte/nouveau-mot-de-passe", get(paul, "/").headers().firstValue("Location").orElseThrow(), "rien d'autre avant");
        assertTrue(get(paul, "/compte/nouveau-mot-de-passe").body().contains("choisi par l'administrateur le 15/06/2026"));
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=motdepasse1&password=motdepasse1&confirm=motdepasse1").statusCode(), "un autre mot de passe");
        assertEquals("/bienvenue", post(paul, "/compte/mot-de-passe", "current=motdepasse1&password=paulpasse1&confirm=paulpasse1")
                .headers().firstValue("Location").orElseThrow());

        String home = get(paul, "/").body();
        assertTrue(home.contains("Budget de Paul"), "vue de Paul par défaut, sans vue Foyer");
        assertTrue(home.contains("?personne=3"), "onglet du compte joint");
        assertFalse(home.contains("?personne=2"), "pas d'onglet Camille");
        assertFalse(home.contains(">Immobilier<"), "immobilier non ouvert");
        // Qui voit quoi : l'icone de l'en-tete, avec les changements depuis la creation du compte
        assertTrue(home.contains("Gestion gère tout le foyer"), home);
        assertTrue(home.contains("Gestion et vous avez accès aux informations de Paul"));
        assertTrue(home.contains("accès aux informations du compte joint « Compte joint »"));
        assertFalse(home.contains("informations de Camille"), "pas les membres qu'il ne voit pas");
        assertTrue(home.contains("Paul : accès ouvert (Paul, Compte joint)") && home.contains("audience-dot"), "changement récent signalé");
        assertEquals(303, post(paul, "/acces/vu", "suite=%2F").statusCode());
        assertFalse(get(paul, "/").body().contains("audience-dot"), "lu");
        assertTrue(get(paul, "/?personne=2").body().contains("Budget de Paul"), "vue de Camille refusée : retour à Paul");
        assertFalse(get(paul, "/budget?personne=2").body().contains("Yoga de Camille"));

        assertEquals(200, get(paul, "/previsionnel.pdf?personne=2").statusCode(), "synthèse de sa vue (Paul, pas Camille)");
        assertEquals(403, get(paul, "/immobilier.pdf").statusCode(), "synthèse immobilière réservée");
        for (String reserved : new String[]{"/immobilier", "/administration/biens", "/administration/repartition", "/administration/utilisateurs", "/administration/foyers",
                "/administration/securite"}) {
            assertEquals(403, get(paul, reserved).statusCode(), reserved);
        }
        assertEquals(403, post(paul, "/administration/membres", "name=Intrus&kind=PERSON").statusCode());

        String expenses = get(paul, "/administration/depenses").body();
        assertTrue(expenses.contains("Club de Paul") && expenses.contains("href=\"/depense/1\""), "sa dépense, modifiable");
        assertTrue(expenses.contains("Courses communes") && !expenses.contains("href=\"/depense/2\">Modifier"), "dépense partagée en lecture seule");
        assertFalse(expenses.contains("Yoga de Camille"), "dépense de Camille invisible");
        assertFalse(expenses.contains("share_2"), "pas de part de Camille dans le formulaire");
        HttpResponse<String> shared = get(paul, "/depense/2");
        assertEquals(200, shared.statusCode(), "dépense partagée consultable");
        assertTrue(shared.body().contains("Lecture seule") && !shared.body().contains("Supprimer la dépense"));
        assertTrue(expenses.contains("<a href=\"/depense/2\" title="), "lien « Voir »");
        assertEquals(403, post(paul, "/depense/2", "label=Pirate" + expense).statusCode());
        assertEquals(404, get(paul, "/depense/3").statusCode());
        assertEquals(303, post(paul, "/depense/1", "label=Club+de+tennis" + expense).statusCode());
        assertEquals(303, post(paul, "/administration/depenses", "label=Essence" + expense).statusCode());
        assertTrue(get(manager, "/administration/depenses").body().contains("Paul 100"), "« parts égales » d'un utilisateur : entre ses membres seulement");

        String incomes = get(paul, "/administration/revenus").body();
        assertFalse(incomes.contains("Salaire Camille"), "revenus de Camille invisibles");
        assertFalse(incomes.contains("Membres du foyer"), "pas de gestion des membres");
        assertEquals(404, get(paul, "/revenu/1").statusCode());
        assertEquals(400, post(paul, "/administration/revenus", "person=2&kind=SALARY&label=X&amount=1&frequency=MONTHLY&start=2026-01-01").statusCode(),
                "revenu pour Camille refusé");
        assertEquals(303, post(paul, "/administration/virements", "from=1&to=3&amount=300&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(400, post(paul, "/administration/virements", "from=2&to=3&amount=300&frequency=MONTHLY&start=2026-01-01").statusCode());

        // Mot de passe : le changer ferme les autres sessions de l'utilisateur
        Visitor otherDevice = login("paul", "paulpasse1");
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=faux&password=nouveau123&confirm=nouveau123").statusCode());
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=paulpasse1&password=nouveau123&confirm=autre1234").statusCode());
        assertEquals(303, post(paul, "/compte/mot-de-passe", "current=paulpasse1&password=nouveau123&confirm=nouveau123").statusCode());
        assertEquals(200, get(paul, "/").statusCode(), "la session qui a changé le mot de passe reste ouverte");
        assertEquals(303, get(otherDevice, "/").statusCode(), "les autres sessions sont fermées");

        // Reinitialisation par l'administrateur : deconnexion, mot de passe provisoire, et l'utilisateur le sait
        assertEquals("/administration/utilisateur/2?ok=mot-de-passe-provisoire",
                post(admin, "/administration/utilisateur/2/mot-de-passe", "password=reprise123").headers().firstValue("Location").orElseThrow());
        assertEquals(303, get(paul, "/").statusCode(), "déconnecté");
        Visitor reset = login("paul", "reprise123");
        assertTrue(get(reset, "/compte/nouveau-mot-de-passe").body().contains("choisi par l'administrateur"));

        // Deconnexion
        assertEquals("/connexion?deconnecte", post(reset, "/deconnexion", "").headers().firstValue("Location").orElseThrow());
        assertEquals(303, get(reset, "/").statusCode(), "session fermée");

        // Journal : connexions et droits sur la page Securite
        String security = get(admin, "/administration/securite").body();
        assertTrue(security.contains("Connexions échouées") && security.contains("<code>paul</code>"), "échec de paul listé");
        assertTrue(security.contains("Paul : accès ouvert (Paul, Compte joint)") && security.contains("réinitialisé"), "journal des droits");
        assertTrue(security.contains("Réseau local"), "adresse locale, sans localisation");
        assertTrue(security.contains("provisoire, à changer"));

        // Desactivation : la session tombe aussitot
        Visitor again = login("paul", "reprise123");
        assertEquals(303, post(admin, "/administration/utilisateur/2", "name=Paul").statusCode());
        assertEquals(303, get(again, "/").statusCode(), "compte désactivé : retour à la connexion");
        assertEquals("/connexion?erreur", post(null, "/connexion", "login=paul&password=reprise123").headers().firstValue("Location").orElseThrow());
    }

    @Test
    void householdFirstThenItsMembersThenAnAccountLinkedToAMember() throws Exception {
        Visitor admin = admin();
        assertEquals("/administration/foyers?ok=foyer#foyer-2", post(admin, "/administration/foyers", "name=Foyer+Martin").headers().firstValue("Location").orElseThrow());
        String households = get(admin, "/administration/foyers").body();
        assertTrue(households.contains("id=\"foyer-2\"") && households.contains("Ajoutez ses membres"), "foyer vide : étapes à suivre");
        assertEquals(303, post(admin, "/administration/foyers/2/membres", "name=Martine&kind=PERSON").statusCode(), "membre ajouté depuis la page Foyers");
        assertTrue(get(admin, "/administration/foyers").body().contains(">Martine<"));
        assertEquals(403, post(admin, "/foyer/choisir", "foyer=2").statusCode(), "l'administrateur n'ouvre pas les foyers");
        assertTrue(get(admin, "/administration/utilisateurs?membre=2:1").body().contains("<option value=\"2:1\" selected>"), "membre présélectionné");

        Visitor martine = account(admin, "martine", "Martine", "membre=2%3A1", 2, null);
        account(admin, "jules", "Jules", "membre=2%3Anouveau", 2, null);
        String after = get(admin, "/administration/foyers").body();
        assertTrue(after.contains(">Jules<") && after.contains("Martine, Jules") || after.contains("Jules, Martine"), "membre créé au nom de l'utilisateur, comptes listés");
        assertTrue(get(martine, "/").body().contains("Budget de Martine"), "voit sa vue sans autre réglage");
        assertEquals(403, get(martine, "/administration/membres").statusCode(), "la gestion des membres reste au gestionnaire");
        assertEquals(400, post(admin, "/administration/utilisateurs", "login=x1&name=X&password=motdepasse1&membre=2%3A99").statusCode(), "membre inconnu");
    }

    @Test
    void anExistingInstallationIsHandedOverToAPersonalAccount() throws Exception {
        Visitor admin = admin();
        assertEquals(303, post(admin, "/administration/foyers/1/membres", "name=William&kind=PERSON").statusCode());
        String households = get(admin, "/administration/foyers").body();
        assertTrue(households.contains("sans gestionnaire") && households.contains("Créer le compte et lui confier"), "reprise proposée");
        assertEquals(400, post(admin, "/administration/foyers/reprise", "name=William&login=william&password=provisoire1").statusCode(), "au moins un foyer");
        assertEquals("/administration/utilisateur/1?ok=reprise",
                post(admin, "/administration/foyers/reprise", "foyer_1=on&name=William&login=william&password=provisoire1").headers().firstValue("Location").orElseThrow());
        assertFalse(get(admin, "/administration/foyers").body().contains("sans gestionnaire"));
        Visitor william = login("william", "provisoire1");
        assertEquals(303, post(william, "/compte/mot-de-passe", "current=provisoire1&password=williampasse1&confirm=williampasse1").statusCode());
        william = login("william", "williampasse1");
        assertEquals(200, get(william, "/administration/biens").statusCode(), "gestionnaire : il saisit tout");
        assertTrue(get(william, "/").body().contains("Vous gérez tout le foyer"));
    }

    @Test
    void anExistingAccountCanBeNamedManager() throws Exception {
        Visitor admin = admin();
        assertEquals(303, post(admin, "/administration/foyers", "name=Foyer+Martin").statusCode());
        Visitor paul = account(admin, "paul", "Paul", "", 1, "access=on&whole=on");
        assertTrue(get(admin, "/administration/foyers").body().contains("Le nommer gestionnaire de ces foyers"), "reprise par un compte existant proposée");
        assertEquals("/administration/foyers?ok=gestionnaire", post(admin, "/administration/foyers/reprise", "foyer_1=on&compte=" + paul.id())
                .headers().firstValue("Location").orElseThrow());
        assertEquals(200, get(paul, "/administration/biens").statusCode(), "gestionnaire du foyer principal");
        assertEquals(303, post(admin, "/administration/foyers/1/gestionnaire", "compte=" + paul.id()).statusCode(), "déjà gestionnaire : rien ne change");

        // Depuis la carte d'un foyer : un seul gestionnaire, que l'on change
        assertTrue(get(admin, "/administration/foyers").body().contains("<option value=\"" + paul.id() + "\" selected>Paul (paul)</option>"), "gestionnaire actuel choisi");
        assertEquals(303, post(admin, "/administration/foyers/2/gestionnaire", "compte=" + paul.id()).statusCode());
        assertTrue(get(paul, "/").body().contains("name=\"foyer\""), "deux foyers : sélecteur");
        assertEquals(400, post(admin, "/administration/foyers/2/gestionnaire", "compte=999").statusCode(), "compte inconnu");
        Visitor camille = account(admin, "camille", "Camille", "", 1, "access=on&whole=on");
        assertEquals(303, post(admin, "/administration/foyers/1/gestionnaire", "compte=" + camille.id()).statusCode());
        assertEquals(200, get(camille, "/administration/biens").statusCode(), "nouvelle gestionnaire");
        assertEquals(403, get(paul, "/administration/biens").statusCode(), "l'ancien est remplacé : ses autres vues restent");
        assertEquals(200, get(paul, "/").statusCode());
        assertEquals(303, post(admin, "/administration/utilisateur/" + paul.id() + "/foyer/1", "access=on&manager=on&whole=on").statusCode());
        assertEquals(403, get(camille, "/administration/biens").statusCode(), "la case « Gestionnaire » remplace aussi");
        assertEquals(303, post(admin, "/administration/foyers/1/gestionnaire", "compte=").statusCode());
        assertEquals(403, get(paul, "/administration/biens").statusCode(), "« Aucun gestionnaire »");
        assertTrue(get(admin, "/administration/securite").body().contains("plus gestionnaire"), "journal");
    }

    @Test
    void aValidAccountDoesNotResetTheFailuresOfItsAddress() throws Exception {
        Visitor admin = admin();
        account(admin, "paul", "Paul", "", 1, "access=on&whole=on");
        for (int i = 0; i < 9; i++) post(null, "/connexion", "login=admin&password=essai" + i);
        login("paul", "paul-motdepasse");
        post(null, "/connexion", "login=admin&password=essai9");
        assertEquals("/connexion?bloque", post(null, "/connexion", "login=admin&password=secret").headers().firstValue("Location").orElseThrow(),
                "10 échecs depuis cette adresse : bloquée, même après une connexion réussie d'un autre compte");
        assertTrue(get(admin, "/administration/securite").body().contains("adresse 127.0.0.1"), "blocage affiché");
    }

    @Test
    void householdsAreSeparateAndSelectable() throws Exception {
        Visitor admin = admin();
        assertEquals("/administration/foyers?ok=foyer#foyer-2", post(admin, "/administration/foyers", "name=Foyer+Martin").headers().firstValue("Location").orElseThrow());
        assertTrue(Files.exists(directory.resolve("foyer-2.sqlite3")), "une base par foyer");
        assertTrue(Files.exists(directory.resolve(App.ACCOUNTS_FILE)));
        Visitor manager = account(admin, "gestion", "Gestion", "", 1, "access=on&manager=on");
        assertEquals(303, post(admin, "/administration/utilisateur/1/foyer/2", "access=on&manager=on").statusCode());

        assertEquals(303, post(manager, "/administration/membres", "name=Paul&kind=PERSON").statusCode());
        assertTrue(get(manager, "/").body().contains("name=\"foyer\""), "sélecteur de foyer à partir de deux foyers");
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=2").statusCode());
        assertFalse(get(manager, "/administration/membres").body().contains("Paul"), "foyer Martin : ses propres membres");
        assertEquals(303, post(manager, "/administration/membres", "name=Martine&kind=PERSON").statusCode());
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=1").statusCode());
        String first = get(manager, "/administration/membres").body();
        assertTrue(first.contains("Paul") && !first.contains("Martine"));

        Visitor martine = account(admin, "martine", "Martine", "", 2, "access=on&whole=on&estate=on");
        assertFalse(get(martine, "/").body().contains("name=\"foyer\""), "un seul foyer : pas de sélecteur");
        assertEquals(200, get(martine, "/immobilier").statusCode(), "immobilier ouvert");
        assertEquals(403, post(martine, "/foyer/choisir", "foyer=1").statusCode(), "foyer non ouvert");

        // Deplacement d'un bien vers le foyer Martin, avec son apport : par un gestionnaire des deux foyers
        assertEquals(303, post(manager, "/bien", "name=Studio&kind=APARTMENT&purchase=100000&purchase_date=2020-01-01&estimate=120000&estimate_date=2025-01-01&sale_fees_pct=8")
                .statusCode());
        assertEquals(303, post(manager, "/bien/1/apports", "name=Apport+initial&amount=20000&date=2020-01-01").statusCode());
        assertTrue(get(manager, "/administration/bien/1").body().contains("Foyer du bien"));
        HttpResponse<String> moved = post(manager, "/administration/bien/1/foyer", "foyer=2");
        assertEquals("/administration/bien/1?ok=deplace", moved.headers().firstValue("Location").orElseThrow());
        assertTrue(get(manager, "/immobilier").body().contains("Studio"), "affiché dans le foyer d'arrivée");
        assertTrue(get(manager, "/bien/1").body().contains("Apport initial"), "avec son apport");
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=1").statusCode());
        assertFalse(get(manager, "/immobilier").body().contains("Studio"), "plus dans le foyer de départ");
        assertEquals(403, post(martine, "/administration/bien/1/foyer", "foyer=1").statusCode(), "réservé aux gestionnaires des deux foyers");
        assertEquals(403, post(admin, "/administration/bien/1/foyer", "foyer=2").statusCode(), "l'administrateur ne voit pas les biens");

        assertEquals(400, post(admin, "/administration/foyers/1/supprimer", "").statusCode(), "le foyer principal reste");
        assertEquals(303, post(admin, "/administration/foyers/2/supprimer", "").statusCode());
        assertFalse(Files.exists(directory.resolve("foyer-2.sqlite3")), "base mise de côté, renommée");
        String archived = get(admin, "/administration/foyers").body();
        Matcher archive = Pattern.compile("name=\"archive\" value=\"(foyer-2\\.sqlite3\\.supprime-[0-9-]+)\"").matcher(archived);
        assertTrue(archive.find(), "foyer supprimé listé, effaçable");
        assertEquals(400, post(admin, "/administration/foyers/archives/effacer", "archive=..%2Fcomptes.sqlite3").statusCode(), "seulement une base mise de côté");
        assertEquals(303, post(admin, "/administration/foyers/archives/effacer", "archive=" + archive.group(1)).statusCode());
        assertFalse(Files.exists(directory.resolve(archive.group(1))), "effacée définitivement");
        assertTrue(Files.exists(directory.resolve(App.ACCOUNTS_FILE)));
        assertEquals(303, get(martine, "/").statusCode(), "plus aucun foyer : déconnectée");
    }
}
