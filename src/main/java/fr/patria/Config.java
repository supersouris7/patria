package fr.patria;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Configuration lue dans l'environnement.
 *
 * @param password     APP_PASSWORD (obligatoire), en clair ou {@code {bcrypt}...}
 * @param databasePath DATABASE_PATH, par defaut {@code /data/immobilier.sqlite3}
 * @param port         HTTP_PORT, par defaut 8080 ; 0 choisit un port libre (tests)
 */
public record Config(String password, Path databasePath, int port, Clock clock) {
    public static Config fromEnvironment(Map<String, String> environment) {
        String password = environment.get("APP_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("APP_PASSWORD doit être défini.");
        return new Config(password, Path.of(environment.getOrDefault("DATABASE_PATH", "/data/immobilier.sqlite3")),
                Integer.parseInt(environment.getOrDefault("HTTP_PORT", "8080")), Clock.systemDefaultZone());
    }
}
