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
            assertTrue(admin.contains("<summary>Utilisateurs</summary>"));
            assertFalse(admin.contains("<summary>Budget</summary>"));
            Layout.CHROME.set(new Layout.Chrome(new Layout.Chrome.Person("Lecteur", false, false, false),
                    List.of(), 1, Layout.Chrome.Alerts.NONE, "/compte"));
            String user = Layout.page("Test", Layout.Section.ACCOUNT, "csrf", "?personne=2", Html.EMPTY).toString();
            assertTrue(user.contains("<summary>Budget</summary>"));
            assertTrue(user.contains("href=\"/budget?personne=2\""));
            assertFalse(user.contains("<summary>Immobilier</summary>"));
            assertFalse(user.contains("href=\"/administration/membres\""));
            assertFalse(user.contains("<summary>Utilisateurs</summary>"));
        } finally {
            Layout.CHROME.remove();
        }
    }
}
