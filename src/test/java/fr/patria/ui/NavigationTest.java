// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationTest {
    @Test
    void toursShowTheBarFirstThenEachPageOnceAndNeverOnErrorPages() {
        try {
            Layout.Chrome.Person person = new Layout.Chrome.Person("Claire", false, true, true, true, 1L, false);
            Layout.CHROME.set(new Layout.Chrome(person, List.of(), 1, Layout.Chrome.Alerts.NONE, "/bilan", java.util.Set.of()));
            assertTrue(Layout.page("Bilan", Layout.Section.HEALTH, "csrf", Html.EMPTY).toString().contains("data-tours=\"barre\""), "la barre seule d'abord");
            Layout.CHROME.set(new Layout.Chrome(person, List.of(), 1, Layout.Chrome.Alerts.NONE, "/bilan", java.util.Set.of("barre")));
            assertTrue(Layout.page("Bilan", Layout.Section.HEALTH, "csrf", Html.EMPTY).toString().contains("data-tours=\"bilan\""), "puis la page");
            assertFalse(Layout.message("Accès réservé", "Non.", "csrf").toString().contains("id=\"visite\""), "jamais sur une page d'erreur");
            Layout.CHROME.set(new Layout.Chrome(person, List.of(), 1, Layout.Chrome.Alerts.NONE, "/bilan", java.util.Set.of("barre", "bilan")));
            assertFalse(Layout.page("Bilan", Layout.Section.HEALTH, "csrf", Html.EMPTY).toString().contains("id=\"visite\""), "une seule fois");
            assertTrue(Tours.known("bilan") && !Tours.known("n-importe-quoi"), "seules les visites connues sont retenues");
        } finally {
            Layout.CHROME.remove();
        }
    }

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
            assertTrue(user.contains("href=\"/?personne=2\"") && user.contains(">Budget<"), "une seule entrée Budget, qui garde la personne");
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
            assertTrue(management.indexOf(">Patrimoine</a>") < management.indexOf(">Économies</a>"));
            assertTrue(management.indexOf("aria-label=\"Bilan de santé\"") < management.indexOf(">Gérer</a>"), "cinq entrées, Gérer en dernier");
            assertTrue(management.indexOf("class=\"account-link") < management.indexOf("class=\"audience\""), "qui voit quoi et thème juste avant Quitter");
            assertTrue(management.contains("aria-label=\"Menu principal\""));
            assertTrue(management.indexOf("data-theme-toggle") < management.indexOf("action=\"/deconnexion\""));
        } finally {
            Layout.CHROME.remove();
        }
    }
}
