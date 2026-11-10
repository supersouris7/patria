// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Sessions en memoire : un redemarrage du serveur deconnecte tout le monde. */
public final class Sessions {
    public static final String COOKIE = "patria_session";
    public static final Duration LIFETIME = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public Sessions(Clock clock) {
        this.clock = clock;
    }

    /** Nouvelle session ; {@code userId} nul pour l'administrateur. */
    public Session create(Long userId, long householdId) {
        long now = clock.millis();
        sessions.values().removeIf(session -> session.expiresAt() < now);
        Session session = new Session(token(), token(), now + LIFETIME.toMillis(), userId, householdId, false);
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

    /** Change le foyer affiche par cette session. */
    public Session select(Session session, long householdId) {
        Session updated = session.withHousehold(householdId);
        sessions.replace(session.id(), updated);
        return updated;
    }

    /** L'alerte HTTP a ete lue : elle ne revient qu'a la prochaine connexion. */
    public void acknowledgeHttp(Session session) {
        sessions.replace(session.id(), session.acknowledgingHttp());
    }

    /** Deconnecte les autres sessions d'un utilisateur (il vient de changer son mot de passe). */
    public void removeOtherSessions(Session current) {
        sessions.values().removeIf(session -> session.userId() != null && session.userId().equals(current.userId()) && !session.id().equals(current.id()));
    }

    /** Deconnecte toutes les sessions d'un utilisateur (compte desactive ou supprime, mot de passe reinitialise). */
    public void removeUser(long userId) {
        sessions.values().removeIf(session -> session.userId() != null && session.userId() == userId);
    }

    /** Sessions ouvertes par compte (cle 0 : l'administrateur), pour la page Securite. */
    public Map<Long, Integer> openByUser() {
        long now = clock.millis();
        Map<Long, Integer> result = new java.util.HashMap<>();
        for (Session session : sessions.values()) {
            if (session.expiresAt() >= now) result.merge(session.userId() == null ? 0L : session.userId(), 1, Integer::sum);
        }
        return result;
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
