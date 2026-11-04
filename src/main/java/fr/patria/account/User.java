package fr.patria.account;

/** Utilisateur enregistre en base (l'administrateur, lui, vient de l'environnement). */
public record User(long id, String login, String name, boolean active) {
}
