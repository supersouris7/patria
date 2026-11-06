package fr.patria.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationTest {
    @Test
    void categoriesRespectAccountRights() {
        try {
            Layout.CHROME.set(new Layout.Chrome(new Layout.Chrome.Person("Admin", true, false, false),
                    List.of(), 1, Layout.Chrome.Alerts.NONE, "/compte"));
            String admin = Layout.page("Test", Layout.Section.ACCOUNT, "csrf", Html.EMPTY).toString();
            assertTrue(admin.contains("<a href=\"/administration/utilisateurs\">Utilisateurs</a>"));
            assertFalse(admin.contains("<summary>Utilisateurs</summary>"), "pas de menu a un seul lien");
            assertFalse(admin.contains("<summary>Budget</summary>"));
            Layout.CHROME.set(new Layout.Chrome(new Layout.Chrome.Person("Lecteur", false, false, false),
                    List.of(), 1, Layout.Chrome.Alerts.NONE, "/compte"));
            String user = Layout.page("Test", Layout.Section.ACCOUNT, "csrf", "?personne=2", Html.EMPTY).toString();
            assertFalse(user.contains("<summary>Budget</summary>"), "acces directs");
            assertTrue(user.contains("href=\"/budget?personne=2\""));
            assertFalse(user.contains("<summary>Immobilier</summary>"));
            assertFalse(user.contains("href=\"/administration/membres\""));
            assertFalse(user.contains("<summary>Utilisateurs</summary>"));
            Layout.CHROME.set(new Layout.Chrome(new Layout.Chrome.Person("Gestionnaire", false, true, true),
                    List.of(), 1, new Layout.Chrome.Alerts(false, List.of("Accès au foyer"), List.of(), null), "/administration/revenus"));
            String management = Layout.adminSection("Test", "csrf", Layout.AdminSection.INCOMES, Html.EMPTY).toString();
            assertTrue(management.contains("href=\"/administration\" class=\"active\" aria-current=\"page\">Gérer"));
            assertTrue(management.contains("href=\"/administration/revenus\" class=\"active\" aria-current=\"page\""));
            assertTrue(management.contains("<aside class=\"admin-nav\""), "menu de gestion lateral");
            assertTrue(management.contains("<a href=\"/immobilier\">Immobilier</a>"));
            assertTrue(management.contains("data-property-links"));
            assertTrue(management.contains("Recherche globale"));
            assertTrue(management.contains("href=\"/immobilier/prets\""));
            assertTrue(management.contains("href=\"/immobilier/taxe-fonciere\""));
            assertTrue(management.indexOf(">Immobilier</a>") < management.indexOf(">Gérer</a>"));
            assertTrue(management.indexOf(">Gérer</a>") < management.indexOf("aria-label=\"Bilan de santé\""));
            assertTrue(management.indexOf("class=\"audience\"") < management.indexOf("class=\"account-link"));
            assertTrue(management.contains("aria-label=\"Menu principal\""));
            assertTrue(management.indexOf("class=\"account-link") < management.indexOf("action=\"/deconnexion\""));
        } finally {
            Layout.CHROME.remove();
        }
    }
}
