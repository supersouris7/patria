// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import fr.patria.TestAccounts.Visitor;
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

    /** L'administrateur : il cree les comptes (il ne voit pas les finances). */
    private TestAccounts.Visitor root;

    /** Le gestionnaire du foyer principal, qui saisit (le premier compte cree). */
    private Visitor manager() throws Exception {
        root = TestAccounts.admin(client, base);
        return TestAccounts.account(client, base, root, "gestion", "Gestion", 1, "access=on&all=on");
    }

    /** « Votre situation » : couple, compte joint, proprietaire avec d'autres biens, epargne ; cree Paul (1), Camille (2), le compte joint (3). */
    private void situation(Visitor manager) throws Exception {
        assertEquals("/assistant/documents", location(post(manager, "/assistant/situation/enregistrer",
                "foyer=couple&prenom=Paul&conjoint=Camille&enfants=&joint=oui&logement=proprietaire&autres=oui&epargne=oui")));
    }

    /** Un compte lie a un membre ({@code membre=1%3A1}), mot de passe provisoire deja change. */
    private Visitor user(String login, String name, String member) throws Exception {
        return TestAccounts.account(client, base, root, login, name, "membre=" + member, 1, null);
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
        return TestAccounts.get(client, base, visitor, path);
    }

    private HttpResponse<String> post(Visitor visitor, String path, String form) throws Exception {
        return TestAccounts.post(client, base, visitor, path, form);
    }

    private HttpResponse<String> upload(Visitor visitor, String path, String name, String csv) throws Exception {
        return TestAccounts.upload(client, base, visitor, path, java.util.Map.of("name", name), "csv", "pret.csv", csv);
    }

    private static String location(HttpResponse<String> response) {
        return TestAccounts.location(response);
    }

    @Test
    void managerFillsTheHouseholdStepByStep() throws Exception {
        Visitor admin = manager();
        String home = get(admin, "/assistant").body();
        assertTrue(home.contains("Commencer") && home.contains("Tout revoir"));
        assertTrue(get(admin, "/assistant/situation").body().contains("Qui compose le budget ?"), "le guide commence par la situation");
        assertEquals(400, post(admin, "/assistant/situation/enregistrer", "foyer=couple&prenom=Paul&joint=non&logement=locataire&autres=non&epargne=non").statusCode(),
                "le prénom du conjoint est demandé");
        situation(admin);
        String path = get(admin, "/assistant").body();
        assertTrue(path.contains("/assistant/biens") && path.contains("/assistant/virements") && !path.contains("/assistant/logement\""),
                "propriétaire avec compte joint : biens et virements, pas de loyer");

        // 1. Documents : coche manuelle, puis coche automatique
        assertTrue(get(admin, "/assistant/documents").body().contains("Les 3 derniers bulletins de salaire"));
        assertTrue(get(admin, "/assistant/documents?q=cr%C3%A9dit+agricole").body().contains("Mes prêts → le crédit") && get(admin, "/assistant/documents?q=lcl").body().contains("Mes relevés et documents → Crédits"), "recherche d'une banque dans les tutoriels");
        assertTrue(get(admin, "/assistant/documents?q=inconnue").body().contains("Rien pour"));
        String help = get(admin, "/assistant/documents/aide").body();
        assertTrue(help.contains("Caisse d&#39;Épargne") || help.contains("Caisse d'Épargne"), "tutoriel complet par banque");
        assertTrue(help.contains("impots.gouv.fr") && help.contains("Trade Republic"));
        assertEquals(303, post(admin, "/assistant/documents/liste", "doc_statements=on").statusCode());
        assertTrue(get(admin, "/assistant/documents").body().contains("name=\"doc_statements\" value=\"on\" checked"), "case cochée gardée");
        assertEquals("/assistant/revenus", location(post(admin, "/assistant/documents/terminer", "")), "après les documents, les revenus");
        assertTrue(get(admin, "/assistant/membres").body().contains("Camille"), "membres créés par la situation, visibles du gestionnaire");
        assertEquals("/assistant/membres", location(post(admin, "/foyer/membres", "name=Leo&kind=PERSON&suite=%2Fassistant%2Fmembres")), "ajout, retour sur l'étape");
        assertTrue(get(admin, "/assistant/revenus").body().contains("Les revenus de Paul"), "à faire ici : une ligne par personne");

        // 2. Membres, 3. Revenus
        assertEquals(303, post(admin, "/assistant/revenus/ajouter", "person=1&kind=SALARY&label=Salaire&amount=2500&frequency=MONTHLY&start=2026-01-01").statusCode());
        assertEquals(400, post(admin, "/assistant/revenus/ajouter", "person=3&kind=SALARY&label=X&amount=1&frequency=MONTHLY&start=2026-01-01").statusCode(),
                "pas de revenu pour le compte joint ici : seulement les personnes ouvertes");
        assertTrue(get(admin, "/assistant/documents").body().contains("name=\"doc_payslips\" value=\"on\" checked disabled"), "coche automatique : salaire saisi");

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
        assertTrue(documents.contains("name=\"doc_amortization\" value=\"on\" checked disabled") && documents.contains("name=\"doc_property-tax\" value=\"on\" checked disabled"));

        // Progression : passer, reprendre, revue
        assertEquals("/assistant/biens", location(post(admin, "/assistant/revenus/passer", "")), "étape passée, suivante à faire (pas de loyer)");
        assertTrue(get(admin, "/assistant").body().contains("href=\"/assistant/biens\">Continuer"), "Continuer : la première étape à faire");
        assertEquals("/assistant/revenus?mode=revue", location(post(admin, "/assistant/documents/terminer", "mode=revue")), "revue : l'étape suivante, même faite");
        String summary = get(admin, "/assistant/recapitulatif").body();
        assertTrue(summary.contains("Il reste") && summary.contains("À compléter") && summary.contains("Fait"), "ce qui reste, en liens directs");
        assertEquals(303, post(admin, "/assistant/recommencer", "").statusCode());
        assertTrue(get(admin, "/assistant").body().contains("Commencer"), "progression effacée");
        assertTrue(get(admin, "/administration/depenses").body().contains("Netflix"), "les données restent");
    }

    @Test
    void possibleErrorsAreCaught() throws Exception {
        Visitor admin = manager();
        situation(admin);

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
        TestAccounts.member(client, base, admin, 1, "name=Paul&kind=PERSON");
        TestAccounts.member(client, base, admin, 1, "name=Camille&kind=PERSON");
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
        TestAccounts.member(client, base, admin, 1, "name=Paul&kind=PERSON");
        TestAccounts.member(client, base, admin, 1, "name=Camille&kind=PERSON");
        Visitor paul = user("paul", "Paul", "1%3A1");

        String home = get(paul, "/assistant").body();
        assertFalse(home.contains("/assistant/membres") || home.contains("/assistant/biens"), "pas d'étapes réservées");
        assertEquals(404, get(paul, "/assistant/membres").statusCode(), "étape réservée au gestionnaire");
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
