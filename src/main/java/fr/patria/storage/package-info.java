/**
 * Persistance SQLite : connexion partagee ({@link fr.patria.storage.Database}), conversions sans flottants
 * ({@link fr.patria.storage.Sql}) et migrations versionnees avec sauvegarde ({@link fr.patria.storage.Migrations}).
 * Chaque module metier possede son propre depot (Repository) qui s'appuie sur ces classes.
 */
package fr.patria.storage;
