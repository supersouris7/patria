package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import fr.patria.TestAccounts.Visitor;
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
        return TestAccounts.get(client, base, visitor, path);
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        return TestAccounts.post(client, base, visitor, path, form);
    }

    private Visitor admin() throws Exception {
        return TestAccounts.admin(client, base);
    }

    /** Compte cree par l'administrateur, mot de passe provisoire change ({@code login-motdepasse}). */
    private Visitor account(Visitor admin, String login, String name, String extra, long household, String access) throws Exception {
        return TestAccounts.account(client, base, admin, login, name, extra, household, access);
    }

    private Visitor login(String login, String password) throws Exception {
        return TestAccounts.login(client, base, login, password);
    }

    @Test
    void userSeesOnlyTheViewsOpenedByTheAdministrator() throws Exception {
        Visitor admin = admin();
        Visitor manager = account(admin, "gestion", "Gestion", "", 1, "access=on&all=on");
        // Foyer principal : Paul (1), Camille (2), compte joint (3), saisis par le gestionnaire
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Camille&kind=PERSON").statusCode());
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Compte+joint&kind=ACCOUNT").statusCode());
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
        assertTrue(households.contains("Gestion · gestionnaire") && !households.contains("Qui voit quoi"), "supervision, pas de réglage des droits");

        // L'administrateur ne cree plus de compte : Paul arrive par l'invitation du gestionnaire, lie au membre Paul
        assertEquals(405, post(admin, "/administration/utilisateurs", "login=paul&name=Paul&password=motdepasse1").statusCode(), "plus de création par l'administrateur");
        assertEquals(400, post(manager, "/foyer/invitations", "membre=aucun&name=X").statusCode(), "sans membre ni case : accès à rien");
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("jeton=([A-Za-z0-9_-]+)").matcher(post(manager, "/foyer/invitations", "membre=1").body());
        assertTrue(link.find());
        String token = link.group(1);
        assertTrue(get(null, "/invitation?jeton=" + token).body().contains("rejoindre le foyer « Mon foyer »"));
        assertEquals(400, post(null, "/invitation", "jeton=" + token + "&name=Paul&login=admin&password=motdepasse1&confirm=motdepasse1").statusCode(), "identifiant de l'administrateur");
        assertEquals(400, post(null, "/invitation", "jeton=" + token + "&name=Paul&login=paul&password=1234&confirm=1234").statusCode(), "mot de passe trop court");
        assertEquals(400, post(null, "/invitation", "jeton=" + token + "&name=Paul&login=paul&password=motdepasse1&confirm=autre1234").statusCode(), "confirmation");
        assertEquals("/compte/securiser", post(null, "/invitation", "jeton=" + token + "&name=Paul&login=paul&password=motdepasse1&confirm=motdepasse1")
                .headers().firstValue("Location").orElseThrow(), "compte créé, double authentification proposée");
        assertEquals(400, post(null, "/invitation", "jeton=" + token + "&name=Autre&login=autre&password=motdepasse1&confirm=motdepasse1").statusCode(), "lien à usage unique");
        // Le gestionnaire ajoute le compte joint a ce que voit Paul
        java.util.Set<String> rights = TestAccounts.checkedRights(client, base, manager);
        rights.add("u2_member_3");
        assertEquals(303, post(manager, "/foyer/droits", String.join("&", rights.stream().map(box -> box + "=on").toList())).statusCode());
        assertEquals("/connexion?erreur", post(null, "/connexion", "login=paul&password=mauvais1").headers().firstValue("Location").orElseThrow());
        Visitor paul = login("paul", "motdepasse1");
        assertEquals(403, post(paul, "/foyer/droits", "u2_member_2=on").statusCode(), "seul le gestionnaire règle les droits");
        assertEquals(403, get(paul, "/foyer").statusCode());
        assertTrue(get(paul, "/compte/securiser").body().contains("Activer maintenant"));
        assertEquals("/bienvenue", post(paul, "/compte/securiser/plus-tard", "").headers().firstValue("Location").orElseThrow(), "plus tard : l'accueil");

        String home = get(paul, "/").body();
        assertTrue(home.contains("Budget de Paul"), "vue de Paul par défaut, sans vue Foyer");
        assertTrue(home.contains("?personne=3"), "onglet du compte joint");
        assertFalse(home.contains("?personne=2"), "pas d'onglet Camille");
        assertFalse(home.contains(">Immobilier<"), "immobilier non ouvert");
        // Qui voit quoi : l'icone de l'en-tete, avec les changements depuis la creation du compte
        assertTrue(home.contains("Gestion voit les totaux du foyer"), home);
        assertTrue(home.contains("Gestion et vous avez accès aux informations de Paul"));
        assertTrue(home.contains("accès aux informations du compte joint « Compte joint »"));
        assertFalse(home.contains("informations de Camille"), "pas les membres qu'il ne voit pas");
        assertTrue(home.contains("Paul : accès modifié (Paul, Compte joint)") && home.contains("audience-dot"), "changement récent signalé");
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
        assertEquals(403, post(paul, "/foyer/membres", "name=Intrus&kind=PERSON").statusCode(), "seul le gestionnaire ajoute des membres");

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
        Visitor otherDevice = login("paul", "motdepasse1");
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=faux&password=nouveau123&confirm=nouveau123").statusCode());
        assertEquals(400, post(paul, "/compte/mot-de-passe", "current=motdepasse1&password=nouveau123&confirm=autre1234").statusCode());
        assertEquals(303, post(paul, "/compte/mot-de-passe", "current=motdepasse1&password=nouveau123&confirm=nouveau123").statusCode());
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
        assertTrue(security.contains("Paul : accès modifié (Paul, Compte joint)") && security.contains("réinitialisé"), "journal des droits");
        assertTrue(security.contains("Réseau local"), "adresse locale, sans localisation");
        assertTrue(security.contains("provisoire, à changer"));

        // Desactivation : la session tombe aussitot
        Visitor again = login("paul", "reprise123");
        assertEquals(303, post(admin, "/administration/utilisateur/2", "name=Paul").statusCode());
        assertEquals(303, get(again, "/").statusCode(), "compte désactivé : retour à la connexion");
        assertEquals("/connexion?erreur", post(null, "/connexion", "login=paul&password=reprise123").headers().firstValue("Location").orElseThrow());
    }

    @Test
    void aNewHouseholdStartsWithItsManagerWhoInvitesItsMembers() throws Exception {
        Visitor admin = admin();
        Visitor martine = TestAccounts.join(client, base, post(admin, "/administration/foyers/lien", "").body(), "martine", "Martine", "Foyer Martin");
        String households = get(admin, "/administration/foyers").body();
        assertTrue(households.contains("id=\"foyer-2\"") && households.contains("Martine · gestionnaire"), "nouveau foyer et son gestionnaire");
        assertTrue(households.contains("<span class=\"badge\">Martine</span>"), "membre créé à son nom");
        assertEquals(403, post(admin, "/foyer/choisir", "foyer=2").statusCode(), "l'administrateur n'ouvre pas les foyers");
        assertEquals(200, get(martine, "/foyer").statusCode(), "elle gère son foyer");

        assertEquals(303, post(martine, "/foyer/membres", "name=Jules&kind=PERSON").statusCode());
        Visitor jules = TestAccounts.join(client, base, post(martine, "/foyer/invitations", "membre=2").body(), "jules", "Jules", "");
        assertTrue(get(jules, "/").body().contains("Budget de Jules"), "voit sa vue sans autre réglage");
        assertEquals(403, post(jules, "/foyer/membres", "name=Intrus&kind=PERSON").statusCode(), "seul le gestionnaire ajoute des membres");
        TestAccounts.join(client, base, post(martine, "/foyer/invitations", "membre=nouveau&name=L%C3%A9o&estate=on").body(), "leo", "Leo", "");
        assertTrue(get(martine, "/foyer").body().contains("Léo"), "membre créé à l'arrivée de l'invité");
        assertEquals(400, post(martine, "/foyer/invitations", "membre=99").statusCode(), "membre inconnu");
    }


    @Test
    void aHouseholdWithoutManagerIsRepairedByTheAdministrator() throws Exception {
        Visitor admin = admin();
        String households = get(admin, "/administration/foyers").body();
        assertTrue(households.contains("Sans gestionnaire") && households.contains("Créer un lien de gestion"), "dépannage proposé");
        Visitor william = TestAccounts.join(client, base, post(admin, "/administration/foyers/1/lien", "").body(), "william", "William", "");
        assertFalse(get(admin, "/administration/foyers").body().contains("sans gestionnaire"));
        assertEquals(400, post(admin, "/administration/foyers/1/lien", "").statusCode(), "foyer géré : l'administrateur ne peut pas s'y ouvrir d'accès");
        assertEquals(200, get(william, "/administration/biens").statusCode(), "gestionnaire repris : il voit tout");
        assertTrue(get(william, "/").body().contains("Vous voyez les totaux du foyer"));

        // Il reste toujours un gestionnaire ; s'il disparait, l'administrateur en nomme un parmi les comptes du foyer
        Visitor paul = TestAccounts.join(client, base, post(william, "/foyer/invitations", "membre=nouveau&name=Paul&whole=on").body(), "paul", "Paul", "");
        assertEquals(400, post(william, "/foyer/droits", "u" + paul.id() + "_whole=on").statusCode(), "au moins un gestionnaire");
        assertEquals(303, post(admin, "/administration/utilisateur/" + william.id() + "/supprimer", "").statusCode());
        assertTrue(get(admin, "/administration/foyers").body().contains("sans gestionnaire"));
        assertEquals(303, post(admin, "/administration/foyers/1/gestionnaire", "compte=" + paul.id()).statusCode());
        paul = login("paul", "paul-motdepasse");
        assertEquals(200, get(paul, "/foyer").statusCode(), "nommé gestionnaire");
    }


    @Test
    void managerSetsEachAccountsRightsIndependently() throws Exception {
        Visitor admin = admin();
        Visitor manager = TestAccounts.manager(client, base);
        Visitor paul = account(admin, "paul", "Paul", "", 1, "access=on&whole=on");
        Visitor camille = account(admin, "camille", "Camille", "", 1, "access=on&whole=on&estate=on");
        assertEquals(200, get(camille, "/administration/biens").statusCode(), "immobilier coché");
        assertEquals(403, get(paul, "/administration/biens").statusCode(), "immobilier non coché");

        // Paul devient gestionnaire aussi : il règle les droits, sans voir davantage
        java.util.Set<String> rights = TestAccounts.checkedRights(client, base, manager);
        rights.add("u" + paul.id() + "_manager");
        assertEquals(303, post(manager, "/foyer/droits", String.join("&", rights.stream().map(box -> box + "=on").toList())).statusCode());
        paul = login("paul", "paul-motdepasse");
        assertEquals(200, get(paul, "/foyer").statusCode());
        assertEquals(403, get(paul, "/administration/biens").statusCode(), "gérer n'est pas voir");
        java.util.Set<String> none = new java.util.LinkedHashSet<>(rights);
        none.removeIf(box -> box.endsWith("_manager"));
        assertEquals(400, post(manager, "/foyer/droits", String.join("&", none.stream().map(box -> box + "=on").toList())).statusCode(), "toujours un gestionnaire");

        // Un gestionnaire peut choisir de ne pas voir les totaux du foyer
        java.util.Set<String> discreet = TestAccounts.checkedRights(client, base, manager);
        discreet.remove("u" + manager.id() + "_whole");
        assertEquals(303, post(manager, "/foyer/droits", String.join("&", discreet.stream().map(box -> box + "=on").toList())).statusCode());
        assertFalse(TestAccounts.checkedRights(client, base, manager).contains("u" + manager.id() + "_whole"));
        assertEquals(200, get(manager, "/foyer").statusCode(), "il gère toujours");

        // Un compte existant crée un second foyer avec un lien de l'administrateur
        Matcher link = Pattern.compile("jeton=([A-Za-z0-9_-]+)").matcher(post(admin, "/administration/foyers/lien", "").body());
        assertTrue(link.find());
        assertEquals(303, post(paul, "/invitation/rejoindre", "jeton=" + link.group(1) + "&foyer=Foyer+Paul").statusCode());
        assertTrue(get(paul, "/administration/revenus").body().contains("name=\"foyer\""), "deux foyers : sélecteur dans Gérer");
        assertTrue(get(admin, "/administration/securite").body().contains("Paul : accès modifié"), "journal");
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
        Visitor manager = TestAccounts.manager(client, base);
        Matcher link = Pattern.compile("jeton=([A-Za-z0-9_-]+)").matcher(post(admin, "/administration/foyers/lien", "").body());
        assertTrue(link.find());
        assertEquals(303, post(manager, "/invitation/rejoindre", "jeton=" + link.group(1) + "&foyer=Foyer+Martin").statusCode(), "second foyer, géré par le même compte");
        assertTrue(Files.exists(directory.resolve("foyer-2.sqlite3")), "une base par foyer");
        assertTrue(Files.exists(directory.resolve(App.ACCOUNTS_FILE)));
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=1").statusCode());

        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
        assertTrue(get(manager, "/administration/revenus").body().contains("name=\"foyer\""), "sélecteur de foyer dans Gérer à partir de deux foyers");
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=2").statusCode());
        assertFalse(get(manager, "/administration/revenus").body().contains(">Paul<"), "foyer Martin : ses propres membres");
        assertEquals(303, post(manager, "/foyer/membres", "name=Martine&kind=PERSON").statusCode());
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=1").statusCode());
        String first = get(manager, "/administration/revenus").body();
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
        assertTrue(get(manager, "/immobilier/biens").body().contains("Studio"), "affiché dans le foyer d'arrivée");
        assertTrue(get(manager, "/bien/1").body().contains("Apport initial"), "avec son apport");
        assertEquals(303, post(manager, "/foyer/choisir", "foyer=1").statusCode());
        assertFalse(get(manager, "/immobilier").body().contains("Studio"), "plus dans le foyer de départ");
        assertEquals(400, post(martine, "/administration/bien/1/foyer", "foyer=1").statusCode(), "accès Immobilier requis dans les deux foyers");
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

    @Test
    void rightsTableSetsExactlyWhoSeesWhat() throws Exception {
        Visitor admin = admin();
        Visitor manager = account(admin, "gestion", "Gestion", "", 1, "access=on&all=on");
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=William&kind=PERSON").statusCode());
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Pascaline&kind=PERSON").statusCode());
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Compte+joint&kind=ACCOUNT").statusCode());
        String expense = "&category=OTHER&amount=40&frequency=MONTHLY&start=2026-01-01";
        assertEquals(303, post(manager, "/administration/depenses", "label=Club+de+William" + expense + "&share_1=100&share_2=0&share_3=0").statusCode());
        assertEquals(303, post(manager, "/administration/depenses", "label=Yoga+de+Pascaline" + expense + "&share_1=0&share_2=100&share_3=0").statusCode());
        assertEquals(303, post(manager, "/administration/depenses", "label=Courses+communes" + expense).statusCode());

        // Tableau des droits : William voit son membre, le compte joint et les totaux ; pas Pascaline, pas l'immobilier
        Visitor william = account(admin, "william", "William", "", 1, "access=on&member_1=on");
        String table = get(manager, "/foyer").body();
        assertTrue(table.contains("rights-table") && table.contains("name=\"u" + william.id() + "_member_2\""), "une ligne par compte, une case par membre");
        java.util.Set<String> rights = TestAccounts.checkedRights(client, base, manager);
        rights.add("u" + william.id() + "_member_3");
        rights.add("u" + william.id() + "_whole");
        assertEquals("/foyer?ok=acces#droits", post(manager, "/foyer/droits", String.join("&", rights.stream().map(box -> box + "=on").toList()))
                .headers().firstValue("Location").orElseThrow());
        assertTrue(get(admin, "/administration/utilisateur/" + william.id()).body().contains("William, Compte joint"), "l'administrateur voit qui voit quoi");
        String lines = get(william, "/administration/depenses").body();
        assertTrue(lines.contains("Club de William") && lines.contains("Courses communes"), "ses lignes et les lignes partagées");
        assertFalse(lines.contains("Yoga de Pascaline"), "pas celles d'un membre non coché");
        String home = get(william, "/").body();
        assertTrue(home.contains("Ligne masquée") && !home.contains("Yoga"), "les totaux restent justes sans le détail");
        assertEquals(403, get(william, "/immobilier").statusCode(), "immobilier non coché");
        assertEquals(403, post(william, "/foyer/membres", "name=Intrus&kind=PERSON").statusCode(), "seul le gestionnaire ajoute des membres");

        // Bilan : par défaut son membre et les comptes joints, puis le choix enregistré
        String health = get(william, "/bilan").body();
        assertTrue(health.contains("Bilan de William, Compte joint") && !health.contains("membre_2"));
        assertEquals(303, post(william, "/bilan/comptes", "membre_1=on").statusCode());
        assertTrue(get(william, "/bilan").body().contains("Bilan de William<"), "choix enregistré");
        assertEquals(303, post(william, "/assistant/bilan/enregistrer", "membre_1=on&membre_3=on").statusCode());
        assertTrue(get(william, "/assistant/bilan").body().contains("membre_3"), "étape du guide");

        // Une ligne vide retire l'accès ; un membre supprimé par le gestionnaire n'est plus coché pour personne
        java.util.Set<String> without = TestAccounts.checkedRights(client, base, manager);
        without.removeIf(box -> box.startsWith("u" + william.id() + "_"));
        assertEquals(303, post(manager, "/foyer/droits", String.join("&", without.stream().map(box -> box + "=on").toList())).statusCode());
        assertTrue(get(william, "/").headers().firstValue("Location").orElseThrow().startsWith("/connexion"), "plus aucun accès : déconnecté");
        assertEquals(303, post(manager, "/foyer/membre/2/supprimer", "").statusCode());
        assertFalse(get(manager, "/foyer").body().contains("_member_2\""), "plus de colonne pour le membre supprimé");
    }

    @Test
    void signupIsOpenOnInvitationOrClosed() throws Exception {
        Visitor admin = admin();
        assertTrue(get(null, "/connexion").body().contains("href=\"/inscription\""));
        assertTrue(get(null, "/inscription").body().contains("sur invitation"), "par défaut : sur invitation");
        assertEquals("/inscription", TestAccounts.location(post(null, "/inscription", "name=Lou&login=lou&password=loupasse1&confirm=loupasse1")), "pas d'inscription libre");
        assertEquals(303, post(admin, "/administration/inscriptions", "mode=open").statusCode());
        assertEquals("/compte/securiser", TestAccounts.location(post(null, "/inscription", "name=Lou&login=lou&password=loupasse1&confirm=loupasse1&foyer=Foyer+Lou")));
        Visitor lou = login("lou", "loupasse1");
        assertTrue(get(lou, "/foyer").body().contains("Foyer Lou"), "gestionnaire de son propre foyer");
        assertEquals(400, post(null, "/inscription", "name=Autre&login=LOU&password=autrepasse1&confirm=autrepasse1").statusCode(), "identifiant déjà pris");
        assertEquals(303, post(null, "/inscription", "name=Bot&login=robot&password=robotpasse1&confirm=robotpasse1&site=spam").statusCode());
        assertEquals("/connexion?erreur", TestAccounts.location(post(null, "/connexion", "login=robot&password=robotpasse1")), "robot ignoré");
        for (int i = 0; i < 3; i++) post(null, "/inscription", "name=X&login=x" + i + "&password=xpassword1&confirm=xpassword1");
        assertEquals(400, post(null, "/inscription", "name=Y&login=y9&password=ypassword1&confirm=ypassword1").statusCode(), "au plus 5 comptes par adresse et par heure");

        // Fermées : plus d'inscription ni d'invitation de gestionnaire ; le lien « créer son foyer » de l'administrateur reste
        assertEquals(303, post(admin, "/administration/inscriptions", "mode=closed").statusCode());
        assertTrue(get(null, "/inscription").body().contains("fermées"));
        assertEquals(400, post(lou, "/foyer/invitations", "membre=nouveau&name=Max&whole=on").statusCode());
        assertTrue(post(admin, "/administration/foyers/lien", "").body().contains("invitation?jeton="));
    }


    @Test
    void savingsBelongToAMemberAndFeedTheBilan() throws Exception {
        Visitor admin = admin();
        Visitor manager = account(admin, "gestion", "Gestion", "", 1, "access=on&all=on");
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Camille&kind=PERSON").statusCode());
        assertEquals(303, post(manager, "/administration/depenses", "label=Loyer&category=HOUSING&amount=1000&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertTrue(get(manager, "/").body().contains("href=\"/epargne\""), "Épargne dans la navigation");

        assertEquals(303, post(manager, "/epargne/compte", "person=1&kind=LIVRET_A&name=Livret+A+de+Paul&balance=2500").statusCode());
        assertEquals(303, post(manager, "/epargne/compte", "person=1&kind=PEA&name=PEA&balance=100&opened_on=2025-01-15").statusCode());
        assertEquals(303, post(manager, "/bourse/ajouter", "symbole=cw8.pa&nom=ETF+Monde&quantity=2&montant=550&prix=pru&compte=2").statusCode());
        assertEquals(400, post(manager, "/bourse/ajouter", "symbole=AI.PA&quantity=1&montant=100&prix=total&compte=1").statusCode(), "pas de bourse sur un livret");
        String page = get(manager, "/epargne").body();
        assertTrue(page.contains("Livret A de Paul") && !page.contains("CW8.PA") && page.contains("épargne de sécurité"), page);
        String stocks = get(manager, "/bourse").body();
        assertTrue(stocks.contains("CW8.PA") && !stocks.contains("Livret A de Paul"), "bourse séparée de l'épargne");
        String wealth = get(manager, "/patrimoine").body().replaceAll("[\\s\\u00a0\\u202f]", "");
        assertTrue(wealth.contains("Patrimoinenet") && wealth.contains("2500€") && wealth.contains("Bourseetcryptos"), "bilan des actifs : livret et bourse");
        assertTrue(page.contains("2,5\u00a0%"), "taux du LEP dans le plan");
        assertEquals(303, post(manager, "/epargne/compte/1", "balance=3000").statusCode());
        assertTrue(get(manager, "/epargne").body().replaceAll("[\\s\\u00a0\\u202f]", "").contains("3000€"));

        String health = get(manager, "/bilan").body();
        assertTrue(health.contains("Épargne de précaution : 3 mois"), health);
        assertTrue(health.contains("PEA : 5 ans le"), "PEA de moins de 5 ans");

        Visitor camille = account(admin, "camille", "Camille", "", 1, "access=on&member_2=on");
        assertFalse(get(camille, "/epargne").body().contains("Livret A de Paul"), "compte d'un membre non coché");
        assertEquals(404, post(camille, "/epargne/compte/1", "balance=1").statusCode());
        assertEquals(400, post(camille, "/epargne/compte", "person=1&kind=LIVRET_A&balance=10").statusCode(), "pas pour un membre non coché");
    }

    @Test
    void guideImportsEverythingFirstThenSortsEachCategory() throws Exception {
        Visitor admin = admin();
        Visitor manager = account(admin, "gestion", "Gestion", "", 1, "access=on&all=on");
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Paul&kind=PERSON").statusCode());
        assertEquals(303, TestAccounts.member(client, base, manager, 1, "name=Compte+joint&kind=ACCOUNT").statusCode());
        String documents = get(manager, "/assistant/documents").body();
        assertTrue(documents.contains("prompt-universel") && documents.contains("REVENU;personne") && documents.contains("Pile"), "phase 1 : un prompt pour tout");
        String answer = """
                ```csv
                REVENU;Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;
                DEPENSE;Netflix;Télécom et abonnements;13,49;Mensuel;01/01/2026;;Paul;Abonnement
                DEPENSE;Disney+;Télécom et abonnements;9,99;Mensuel;01/01/2026;;;Doublon possible
                DEPENSE;Loyer;Logement;900;Mensuel;01/01/2026;;;
                VIREMENT;Paul;Compte joint;Participation;600;Mensuel;01/01/2026;
                EPARGNE;Paul;PEA;PEA;100;31/10/2026;
                BOURSE;PEA;AI.PA;Air Liquide;5;780
                n'importe quoi
                ```""";
        HttpResponse<String> added = post(manager, "/assistant/documents/importer", "texte=" + java.net.URLEncoder.encode(answer, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, added.statusCode());
        assertTrue(added.body().contains("7 lignes ajoutées à la pile"), added.body().replaceAll("(?s).*?(<p class=\"notice.{0,400}).*", "$1"));
        assertTrue(get(manager, "/administration/depenses").body().contains("Aucune dépense") || !get(manager, "/administration/depenses").body().contains("Netflix"),
                "rien n'entre dans le budget avant validation");
        assertEquals("/assistant/revenus", location(post(manager, "/assistant/documents/terminer", "")), "Suivant : l'étape juste après");
        assertEquals("/assistant/logement", location(post(manager, "/assistant/revenus/terminer", "")));
        assertEquals("/assistant/revenus", location(post(manager, "/assistant/documents/terminer", "")), "même déjà faite, l'étape suivante n'est pas sautée");

        String incomes = get(manager, "/assistant/revenus").body();
        assertTrue(incomes.contains("À trier") && incomes.contains("Paul · Salaire"));
        assertTrue(sort(manager, "/assistant/revenus", incomes, true).contains("1 ligne validée"));
        assertTrue(get(manager, "/administration/revenus").body().contains("Salaire"));

        String fixed = get(manager, "/assistant/depenses").body();
        assertTrue(fixed.contains("Netflix") && fixed.contains("Disney+") && !fixed.contains("Loyer ·"), "le loyer se trie à l'étape Logement");
        Matcher disney = Pattern.compile("name=\"garder_(\\d+)\" value=\"on\" data-import-row data-groups=\"c:[^\"]*\">[^<]*Disney").matcher(fixed);
        assertTrue(disney.find(), "doublon possible décoché d'avance");
        assertTrue(sort(manager, "/assistant/depenses", fixed, false).contains("1 ligne validée, 1 refusée"));
        String expenses = get(manager, "/administration/depenses").body();
        assertTrue(expenses.contains("Netflix") && !expenses.contains("Disney"));
        assertTrue(get(manager, "/assistant/logement").body().contains("Loyer ·"));

        String savings = get(manager, "/assistant/epargne").body();
        assertTrue(sort(manager, "/assistant/epargne", savings, true).contains("2 lignes validées"), "le PEA puis sa ligne de bourse");
        assertTrue(get(manager, "/bourse").body().contains("Air Liquide"), "la bourse a sa propre page");
        assertTrue(sort(manager, "/assistant/virements", get(manager, "/assistant/virements").body(), true).contains("1 ligne validée"));
        assertTrue(sort(manager, "/assistant/logement", get(manager, "/assistant/logement").body(), false).contains("1 ligne validée"));
        assertTrue(get(manager, "/assistant/documents").body().contains("Vide pour l"), "pile vidée");
    }

    /** Envoie le tri d'une etape : chaque ligne affichee, cochee seulement si elle l'est (ou toutes si {@code all}). */
    private String sort(Visitor visitor, String step, String page, boolean all) throws Exception {
        StringBuilder form = new StringBuilder("etape=" + step);
        Matcher rows = Pattern.compile("name=\"garder_(\\d+)\" value=\"on\"( checked)?").matcher(page);
        while (rows.find()) {
            form.append("&vu_").append(rows.group(1)).append("=on");
            if (all || rows.group(2) != null) form.append("&garder_").append(rows.group(1)).append("=on");
        }
        HttpResponse<String> response = post(visitor, "/assistant/tri", form.toString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static String location(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }
}
