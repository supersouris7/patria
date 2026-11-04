package fr.patria.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite les essais de mot de passe sur 15 minutes : {@value #MAX_FAILURES} echecs par adresse, et
 * {@value #MAX_FAILURES_PER_LOGIN} par identifiant, toutes adresses confondues (contre une attaque repartie ou une
 * adresse usurpee). Une connexion reussie n'efface que le compteur de son identifiant : un compte valide ne permet
 * pas de remettre a zero les essais de son adresse pour attaquer un autre compte.
 */
final class LoginThrottle {
    static final int MAX_FAILURES = 10;
    static final int MAX_FAILURES_PER_LOGIN = 30;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private record Attempts(int count, long since) {
    }

    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Vrai si l'adresse ou l'identifiant a trop d'echecs recents. */
    boolean isBlocked(String client, String login) {
        return blocked("ip:" + client, MAX_FAILURES) || blocked("login:" + normalize(login), MAX_FAILURES_PER_LOGIN);
    }

    void recordFailure(String client, String login) {
        record("ip:" + client);
        record("login:" + normalize(login));
    }

    /** Connexion reussie : seul le compteur de cet identifiant repart a zero. */
    void reset(String login) {
        failures.remove("login:" + normalize(login));
    }

    private static String normalize(String login) {
        return login == null ? "" : login.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean blocked(String key, int maximum) {
        Attempts attempts = failures.get(key);
        if (attempts == null) return false;
        if (expired(attempts)) {
            failures.remove(key);
            return false;
        }
        return attempts.count() >= maximum;
    }

    private void record(String client) {
        if (failures.size() > MAX_TRACKED) failures.values().removeIf(this::expired);
        long now = clock.millis();
        failures.merge(client, new Attempts(1, now), (current, ignored) -> expired(current) ? new Attempts(1, now) : new Attempts(current.count() + 1, current.since()));
    }

    private boolean expired(Attempts attempts) {
        return attempts.since() + WINDOW.toMillis() < clock.millis();
    }
}
