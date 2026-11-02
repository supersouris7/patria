package fr.patria.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Sessions en memoire : un redemarrage du serveur deconnecte tout le monde (application mono-utilisateur). */
public final class Sessions {
    public static final String COOKIE = "patria_session";
    public static final Duration LIFETIME = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public Sessions(Clock clock) {
        this.clock = clock;
    }

    public Session create() {
        long now = clock.millis();
        sessions.values().removeIf(session -> session.expiresAt() < now);
        Session session = new Session(token(), token(), now + LIFETIME.toMillis());
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<Session> find(String id) {
        if (id == null) return Optional.empty();
        Session session = sessions.get(id);
        if (session == null || session.expiresAt() < clock.millis()) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void remove(Session session) {
        sessions.remove(session.id());
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
