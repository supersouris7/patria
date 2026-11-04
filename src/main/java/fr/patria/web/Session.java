package fr.patria.web;

/**
 * Session d'une personne connectee ; {@code csrf} protege tous ses formulaires.
 *
 * @param userId      utilisateur enregistre en base, {@code null} pour l'administrateur (compte de l'environnement)
 * @param householdId foyer affiche, choisi dans l'en-tete
 */
public record Session(String id, String csrf, long expiresAt, Long userId, long householdId) {
    public boolean isAdmin() {
        return userId == null;
    }

    Session withHousehold(long household) {
        return new Session(id, csrf, expiresAt, userId, household);
    }
}
