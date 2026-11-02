package fr.patria.web;

/** Session d'une personne connectee ; {@code csrf} protege tous ses formulaires. */
public record Session(String id, String csrf, long expiresAt) {
}
