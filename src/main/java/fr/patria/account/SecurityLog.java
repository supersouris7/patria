package fr.patria.account;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Journaux de la base des comptes : connexions (gardees {@value #KEPT_DAYS} jours), droits, et localisation des adresses
 * IP (cache). Tables creees par {@link AccountRepository} (migration v2).
 */
public final class SecurityLog {
    private final Database database;

    public SecurityLog(Database database) {
        this.database = database;
    }

    // ---- Journaux ----

    /** Connexion reussie ou echouee ; les evenements de plus de {@value #KEPT_DAYS} jours sont effaces. */
    public void recordLogin(java.time.LocalDateTime at, String login, Long userId, boolean success, String ip) {
        database.transaction(connection -> {
            Sql.update(connection, "INSERT INTO login_events (at, login, user_id, success, ip) VALUES (?, ?, ?, ?, ?)",
                    at.toString(), login.length() > 60 ? login.substring(0, 60) : login, userId, success ? 1 : 0, ip);
            Sql.update(connection, "DELETE FROM login_events WHERE at < ?", at.minusDays(KEPT_DAYS).toString());
            return null;
        });
    }

    /** Duree de conservation du journal des connexions. */
    public static final int KEPT_DAYS = 90;

    /** Une connexion du journal. */
    public record LoginEvent(java.time.LocalDateTime at, String login, Long userId, boolean success, String ip) {
    }

    /** Les {@code limit} dernieres connexions, reussies ({@code success}) ou echouees. */
    public List<LoginEvent> loginEvents(boolean success, int limit) {
        return database.query(connection -> Sql.list(connection, "SELECT at, login, user_id, success, ip FROM login_events WHERE success = ? ORDER BY at DESC, id DESC LIMIT ?",
                row -> new LoginEvent(java.time.LocalDateTime.parse(row.getString(1)), row.getString(2), row.getObject(3) == null ? null : row.getLong(3),
                        row.getInt(4) == 1, row.getString(5)), success ? 1 : 0, limit));
    }

    /** Nombre d'echecs depuis ce moment. */
    public int failuresSince(java.time.LocalDateTime since) {
        return database.query(connection -> Sql.list(connection, "SELECT count(*) FROM login_events WHERE success = 0 AND at >= ?", row -> row.getInt(1), since.toString()))
                .getFirst();
    }

    /** Derniere connexion reussie de chaque compte ({@code null} : l'administrateur). */
    public Map<Long, java.time.LocalDateTime> lastLogins() {
        Map<Long, java.time.LocalDateTime> result = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT coalesce(user_id, 0), max(at) FROM login_events WHERE success = 1 GROUP BY coalesce(user_id, 0)",
                row -> result.put(row.getLong(1), java.time.LocalDateTime.parse(row.getString(2)))));
        return result;
    }

    /** Une ligne du journal des droits. */
    public record AuditEntry(java.time.LocalDateTime at, String actor, String action, Long householdId, Long userId, String detail) {
    }

    public void audit(java.time.LocalDateTime at, String actor, String action, Long householdId, Long userId, String detail) {
        database.query(connection -> Sql.update(connection, "INSERT INTO audit_log (at, actor, action, household_id, user_id, detail) VALUES (?, ?, ?, ?, ?, ?)",
                at.toString(), actor, action, householdId, userId, detail));
    }

    public List<AuditEntry> auditLog(int limit) {
        return database.query(connection -> Sql.list(connection, "SELECT at, actor, action, household_id, user_id, detail FROM audit_log ORDER BY at DESC, id DESC LIMIT ?",
                SecurityLog::auditEntry, limit));
    }

    /** Changements d'acces a un foyer apres ce moment, les plus recents d'abord. */
    public List<AuditEntry> accessChanges(long householdId, java.time.LocalDateTime after) {
        return database.query(connection -> Sql.list(connection, """
                SELECT at, actor, action, household_id, user_id, detail FROM audit_log
                WHERE household_id = ? AND action = 'ACCESS' AND at > ? ORDER BY at DESC, id DESC LIMIT 20""", SecurityLog::auditEntry, householdId, after.toString()));
    }

    private static AuditEntry auditEntry(ResultSet row) throws SQLException {
        return new AuditEntry(java.time.LocalDateTime.parse(row.getString(1)), row.getString(2), row.getString(3), row.getObject(4) == null ? null : row.getLong(4),
                row.getObject(5) == null ? null : row.getLong(5), row.getString(6));
    }

    // ---- Localisation des adresses IP (cache) ----

    public Optional<String[]> ipLocation(String ip) {
        return database.query(connection -> Sql.list(connection, "SELECT country, city FROM ip_locations WHERE ip = ?", row -> new String[]{row.getString(1), row.getString(2)}, ip))
                .stream().findFirst();
    }

    public void saveIpLocation(String ip, String country, String city, LocalDate today) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO ip_locations (ip, country, city, looked_up_on) VALUES (?, ?, ?, ?)
                ON CONFLICT (ip) DO UPDATE SET country = excluded.country, city = excluded.city, looked_up_on = excluded.looked_up_on""", ip, country, city, today));
    }

    // ---- Connexions d'un compte (en-tete, Mon compte) ----

    /** Les {@code limit} dernieres connexions reussies ou echouees de cet identifiant, avant {@code before}. */
    public List<LoginEvent> loginEventsOf(String login, java.time.LocalDateTime before, int limit) {
        return database.query(connection -> Sql.list(connection, """
                SELECT at, login, user_id, success, ip FROM login_events WHERE lower(login) IN (lower(?), lower(?) || ' (bloqué)', lower(?) || ' (code 2fa)') AND at < ?
                ORDER BY at DESC, id DESC LIMIT ?""",
                row -> new LoginEvent(java.time.LocalDateTime.parse(row.getString(1)), row.getString(2), row.getObject(3) == null ? null : row.getLong(3),
                        row.getInt(4) == 1, row.getString(5)), login, login, login, before.toString(), limit));
    }
}
