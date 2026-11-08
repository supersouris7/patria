package fr.patria;

import fr.patria.storage.Encryption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Configuration lue dans l'environnement.
 *
 * @param password      APP_PASSWORD (obligatoire), en clair ou {@code {bcrypt}...} : mot de passe de l'administrateur
 * @param adminLogin    ADMIN_LOGIN, identifiant de l'administrateur, par defaut {@code admin}
 * @param databasePath  DATABASE_PATH, par defaut {@code /data/immobilier.sqlite3}
 * @param port          HTTP_PORT, par defaut 8080 ; 0 choisit un port libre (tests)
 * @param offline       OFFLINE=true : aucun appel externe (data.gouv.fr, BCE) ; pas de prix du marche ni de taux
 * @param encryptionKey DB_ENCRYPTION_KEY (ou le contenu du fichier DB_ENCRYPTION_KEY_FILE) : cle de chiffrement des
 *                      bases, au moins 16 caracteres ; {@code null} : bases en clair
 * @param supportUrl    KOFI_URL : page Ko-fi pour soutenir le developpement, remplace celle du projet ; vide la masque
 * @param resetAdminTwoFactor ADMIN_2FA_RESET=true : retire la double authentification de l'administrateur au
 *                      demarrage (telephone perdu) ; a retirer ensuite de la configuration
 */
public record Config(String password, String adminLogin, Path databasePath, int port, Clock clock, boolean offline, String encryptionKey,
                     String supportUrl, boolean resetAdminTwoFactor) {
    /** Page Ko-fi du projet, affichee par defaut (KOFI_URL la remplace, KOFI_URL vide la masque). */
    static final String PROJECT_SUPPORT_URL = "https://ko-fi.com/supersouris7";

    public Config {
        if (encryptionKey != null && encryptionKey.length() < Encryption.MIN_KEY) {
            throw new IllegalStateException("DB_ENCRYPTION_KEY doit faire au moins " + Encryption.MIN_KEY + " caractères.");
        }
        if (supportUrl != null && !supportUrl.isEmpty() && !supportUrl.matches("https://ko-fi\\.com/[A-Za-z0-9_]{1,40}")) {
            throw new IllegalStateException("KOFI_URL doit être une adresse https://ko-fi.com/<nom>.");
        }
    }



    /** Sans le mode hors ligne ni chiffrement (tests). */
    public Config(String password, String adminLogin, Path databasePath, int port, Clock clock) {
        this(password, adminLogin, databasePath, port, clock, false);
    }

    public Config(String password, String adminLogin, Path databasePath, int port, Clock clock, boolean offline) {
        this(password, adminLogin, databasePath, port, clock, offline, null, PROJECT_SUPPORT_URL);
    }

    public Config(String password, String adminLogin, Path databasePath, int port, Clock clock, boolean offline, String encryptionKey, String supportUrl) {
        this(password, adminLogin, databasePath, port, clock, offline, encryptionKey, supportUrl, false);
    }

    /** Lien « Soutenir », ou {@code null} s'il est masque. */
    public String support() {
        return supportUrl == null || supportUrl.isEmpty() ? null : supportUrl;
    }

    public static Config fromEnvironment(Map<String, String> environment) {
        String password = environment.get("APP_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("APP_PASSWORD doit être défini.");
        String adminLogin = environment.getOrDefault("ADMIN_LOGIN", "admin").trim();
        String support = environment.containsKey("KOFI_URL") ? environment.get("KOFI_URL").trim() : PROJECT_SUPPORT_URL;
        return new Config(password, adminLogin.isEmpty() ? "admin" : adminLogin, Path.of(environment.getOrDefault("DATABASE_PATH", "/data/immobilier.sqlite3")),
                Integer.parseInt(environment.getOrDefault("HTTP_PORT", "8080")), Clock.systemDefaultZone(),
                "true".equalsIgnoreCase(environment.getOrDefault("OFFLINE", "").trim()), key(environment), support,
                "true".equalsIgnoreCase(environment.getOrDefault("ADMIN_2FA_RESET", "").trim()));
    }

    /** DB_ENCRYPTION_KEY, ou le fichier DB_ENCRYPTION_KEY_FILE (secret Docker) ; {@code null} si aucun. */
    static String key(Map<String, String> environment) {
        String file = environment.get("DB_ENCRYPTION_KEY_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file.trim())).strip();
            } catch (IOException exception) {
                throw new IllegalStateException("DB_ENCRYPTION_KEY_FILE illisible : " + file, exception);
            }
        }
        String key = environment.get("DB_ENCRYPTION_KEY");
        return key == null || key.isBlank() ? null : key;
    }
}
