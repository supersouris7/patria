package fr.patria;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Configuration lue dans l'environnement.
 *
 * @param password     APP_PASSWORD (obligatoire), en clair ou {@code {bcrypt}...} : mot de passe de l'administrateur
 * @param adminLogin   ADMIN_LOGIN, identifiant de l'administrateur, par defaut {@code admin}
 * @param databasePath DATABASE_PATH, par defaut {@code /data/immobilier.sqlite3}
 * @param port         HTTP_PORT, par defaut 8080 ; 0 choisit un port libre (tests)
 */
public record Config(String password, String adminLogin, Path databasePath, int port, Clock clock) {
    public static Config fromEnvironment(Map<String, String> environment) {
        String password = environment.get("APP_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("APP_PASSWORD doit être défini.");
        String adminLogin = environment.getOrDefault("ADMIN_LOGIN", "admin").trim();
        return new Config(password, adminLogin.isEmpty() ? "admin" : adminLogin, Path.of(environment.getOrDefault("DATABASE_PATH", "/data/immobilier.sqlite3")),
                Integer.parseInt(environment.getOrDefault("HTTP_PORT", "8080")), Clock.systemDefaultZone());
    }
}
