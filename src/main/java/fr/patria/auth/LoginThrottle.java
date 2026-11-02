package fr.patria.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Limite les essais de mot de passe : {@value #MAX_FAILURES} echecs par adresse sur 15 minutes. */
final class LoginThrottle {
    static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private record Attempts(int count, long since) {
    }

    private final Map<String, Attempts> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    boolean isBlocked(String client) {
        Attempts attempts = failures.get(client);
        if (attempts == null) return false;
        if (expired(attempts)) {
            failures.remove(client);
            return false;
        }
        return attempts.count() >= MAX_FAILURES;
    }

    void recordFailure(String client) {
        if (failures.size() > MAX_TRACKED) failures.values().removeIf(this::expired);
        long now = clock.millis();
        failures.merge(client, new Attempts(1, now), (current, ignored) -> expired(current) ? new Attempts(1, now) : new Attempts(current.count() + 1, current.since()));
    }

    void reset(String client) {
        failures.remove(client);
    }

    private boolean expired(Attempts attempts) {
        return attempts.since() + WINDOW.toMillis() < clock.millis();
    }
}
