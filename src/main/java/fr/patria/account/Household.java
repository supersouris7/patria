package fr.patria.account;

/**
 * Foyer : ses donnees sont dans leur propre base SQLite.
 *
 * @param file nom du fichier de la base, a cote de DATABASE_PATH ; {@code null} pour le foyer principal (DATABASE_PATH)
 */
public record Household(long id, String name, String file) {
    public boolean isPrimary() {
        return file == null;
    }
}
