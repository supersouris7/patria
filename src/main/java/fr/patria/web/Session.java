// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.web;

/**
 * Session d'une personne connectee ; {@code csrf} protege tous ses formulaires.
 *
 * @param userId      utilisateur enregistre en base, {@code null} pour l'administrateur (compte de l'environnement)
 * @param householdId foyer affiche, choisi dans l'en-tete
 * @param httpAcknowledged alerte « connexion non chiffree (HTTP) » lue pour cette session
 */
public record Session(String id, String csrf, long expiresAt, Long userId, long householdId, boolean httpAcknowledged) {
    public boolean isAdmin() {
        return userId == null;
    }

    Session withHousehold(long household) {
        return new Session(id, csrf, expiresAt, userId, household, httpAcknowledged);
    }

    Session acknowledgingHttp() {
        return new Session(id, csrf, expiresAt, userId, householdId, true);
    }
}
