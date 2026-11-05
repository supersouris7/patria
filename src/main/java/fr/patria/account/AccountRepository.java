package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Foyers, utilisateurs et droits, dans la base des comptes ({@code comptes.sqlite3}, a cote de DATABASE_PATH).
 * Mots de passe chiffres avec bcrypt.
 */
public final class AccountRepository {
    static final int MIN_PASSWORD = 8;
    private static final int LATEST = 2;
    /** Hache de reference : un identifiant inconnu coute le meme temps qu'un mauvais mot de passe. */
    private static final int COST = 12;
    private static final String DUMMY_HASH = BCrypt.hashpw("patria", BCrypt.gensalt(COST));
    private final Database database;

    public AccountRepository(Database database) {
        this.database = database;
        database.query(connection -> {
            migrate(connection);
            return null;
        });
    }

    private static void migrate(Connection connection) throws SQLException {
        int version;
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery("PRAGMA user_version")) {
            version = row.getInt(1);
        }
        if (version > LATEST) throw new IllegalStateException("Base des comptes créée par une version plus récente de Patria.");
        if (version < 1) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE households (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            file TEXT UNIQUE,
                            created_on TEXT NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE users (
                            id INTEGER PRIMARY KEY,
                            login TEXT NOT NULL UNIQUE COLLATE NOCASE,
                            name TEXT NOT NULL,
                            password_hash TEXT NOT NULL,
                            active INTEGER NOT NULL DEFAULT 1,
                            created_on TEXT NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE user_households (
                            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
                            whole_household INTEGER NOT NULL DEFAULT 0,
                            real_estate INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (user_id, household_id)
                        )""");
                statement.execute("""
                        CREATE TABLE user_members (
                            user_id INTEGER NOT NULL,
                            household_id INTEGER NOT NULL,
                            person_id INTEGER NOT NULL,
                            PRIMARY KEY (user_id, household_id, person_id),
                            FOREIGN KEY (user_id, household_id) REFERENCES user_households(user_id, household_id) ON DELETE CASCADE
                        )""");
                statement.execute("PRAGMA user_version = 1");
            }
        }
        if (version < 2) {
            // Gestionnaires ; accueil (welcomed_on, settings pour l'administrateur) ; mot de passe choisi par
            // l'administrateur, a changer ; derniere lecture de « qui voit quoi » ; journaux de connexion et des droits ;
            // localisation des adresses IP (cache).
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE user_households ADD COLUMN manager INTEGER NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE users ADD COLUMN welcomed_on TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE users ADD COLUMN password_set_by_admin_on TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN audience_seen_at TEXT");
                statement.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("""
                        CREATE TABLE login_events (
                            id INTEGER PRIMARY KEY,
                            at TEXT NOT NULL,
                            login TEXT NOT NULL,
                            user_id INTEGER,
                            success INTEGER NOT NULL,
                            ip TEXT NOT NULL
                        )""");
                statement.execute("CREATE INDEX login_events_at ON login_events(at)");
                statement.execute("""
                        CREATE TABLE audit_log (
                            id INTEGER PRIMARY KEY,
                            at TEXT NOT NULL,
                            actor TEXT NOT NULL,
                            action TEXT NOT NULL,
                            household_id INTEGER,
                            user_id INTEGER,
                            detail TEXT NOT NULL
                        )""");
                statement.execute("CREATE INDEX audit_log_household ON audit_log(household_id, at)");
                statement.execute("CREATE TABLE ip_locations (ip TEXT PRIMARY KEY, country TEXT NOT NULL, city TEXT NOT NULL, looked_up_on TEXT NOT NULL)");
                statement.execute("PRAGMA user_version = 2");
            }
        }
    }

    // ---- Foyers ----

    public List<Household> households() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM households ORDER BY id", AccountRepository::household));
    }

    public Optional<Household> household(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM households WHERE id = ?", AccountRepository::household, id)).stream().findFirst();
    }

    /** Premier demarrage : la base existante (DATABASE_PATH) devient le foyer principal. */
    public void ensurePrimary(String name, LocalDate today) {
        database.transaction(connection -> {
            if (Sql.list(connection, "SELECT id FROM households", row -> row.getLong(1)).isEmpty()) {
                Sql.update(connection, "INSERT INTO households (name, file, created_on) VALUES (?, NULL, ?)", name, today);
            }
            return null;
        });
    }

    /** Nouveau foyer ; sa base s'appellera {@code foyer-<id>.sqlite3}. */
    public Household createHousehold(String name, LocalDate today) {
        return database.transaction(connection -> {
            long id = Sql.insert(connection, "INSERT INTO households (name, file, created_on) VALUES (?, ?, ?) RETURNING id", name, "en-cours-" + System.nanoTime(), today);
            String file = "foyer-" + id + ".sqlite3";
            Sql.update(connection, "UPDATE households SET file = ? WHERE id = ?", file, id);
            return new Household(id, name, file);
        });
    }

    public void renameHousehold(long id, String name) {
        database.query(connection -> Sql.update(connection, "UPDATE households SET name = ? WHERE id = ?", name, id));
    }

    public void deleteHousehold(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM households WHERE id = ?", id));
    }

    private static Household household(ResultSet row) throws SQLException {
        return new Household(row.getLong("id"), row.getString("name"), row.getString("file"));
    }

    // ---- Utilisateurs ----

    public List<User> users() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM users ORDER BY name COLLATE NOCASE, login", AccountRepository::user));
    }

    public Optional<User> user(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM users WHERE id = ?", AccountRepository::user, id)).stream().findFirst();
    }

    public long createUser(String login, String name, String password, LocalDate today) {
        checkPassword(password);
        return database.transaction(connection -> {
            if (!Sql.list(connection, "SELECT id FROM users WHERE login = ?", row -> row.getLong(1), login).isEmpty()) {
                throw new ValidationException("L'identifiant « " + login + " » est déjà pris.");
            }
            // Mot de passe choisi par l'administrateur : a changer a la premiere connexion.
            return Sql.insert(connection, """
                    INSERT INTO users (login, name, password_hash, created_on, must_change_password, password_set_by_admin_on)
                    VALUES (?, ?, ?, ?, 1, ?) RETURNING id""", login, name, hash(password), today, today);
        });
    }

    public void updateUser(long id, String name, boolean active) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET name = ?, active = ? WHERE id = ?", name, active ? 1 : 0, id));
    }

    /** Mot de passe choisi par l'utilisateur lui-meme. */
    public void setPassword(long id, String password) {
        checkPassword(password);
        database.query(connection -> Sql.update(connection, "UPDATE users SET password_hash = ?, must_change_password = 0 WHERE id = ?", hash(password), id));
    }

    /** Reinitialisation par l'administrateur : a changer a la prochaine connexion, date retenue pour l'en informer. */
    public void resetPassword(long id, String password, LocalDate today) {
        checkPassword(password);
        database.query(connection -> Sql.update(connection, "UPDATE users SET password_hash = ?, must_change_password = 1, password_set_by_admin_on = ? WHERE id = ?",
                hash(password), today, id));
    }

    /** Date a laquelle l'administrateur a choisi le mot de passe actuel, s'il doit encore etre change ; vide sinon. */
    public Optional<LocalDate> passwordToChange(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT password_set_by_admin_on FROM users WHERE id = ? AND must_change_password = 1",
                row -> row.getString(1) == null ? LocalDate.MIN : LocalDate.parse(row.getString(1)), id)).stream().findFirst();
    }

    public void deleteUser(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM users WHERE id = ?", id));
    }

    /** Utilisateur actif dont l'identifiant et le mot de passe correspondent. */
    public Optional<User> authenticate(String login, String password) {
        List<String[]> rows = database.query(connection -> Sql.list(connection, "SELECT id, password_hash, active FROM users WHERE login = ?",
                row -> new String[]{row.getString(1), row.getString(2), row.getString(3)}, login == null ? "" : login.trim()));
        String entered = password == null ? "" : password;
        if (rows.isEmpty()) {
            BCrypt.checkpw(entered, DUMMY_HASH);
            return Optional.empty();
        }
        String[] row = rows.getFirst();
        if (!BCrypt.checkpw(entered, row[1]) || !"1".equals(row[2])) return Optional.empty();
        return user(Long.parseLong(row[0]));
    }

    /** Vrai si ce mot de passe est celui de l'utilisateur (changement de mot de passe). */
    public boolean checkPassword(long id, String password) {
        List<String> hashes = database.query(connection -> Sql.list(connection, "SELECT password_hash FROM users WHERE id = ?", row -> row.getString(1), id));
        return !hashes.isEmpty() && BCrypt.checkpw(password == null ? "" : password, hashes.getFirst());
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) throw new ValidationException("Le mot de passe doit faire au moins " + MIN_PASSWORD + " caractères.");
        // bcrypt ne lit que les 72 premiers octets : au-dela, la fin du mot de passe serait ignoree.
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ValidationException("Le mot de passe est trop long (72 octets au plus, environ 70 caractères sans accents).");
        }
    }

    private static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(COST));
    }

    private static User user(ResultSet row) throws SQLException {
        return new User(row.getLong("id"), row.getString("login"), row.getString("name"), row.getInt("active") == 1);
    }

    // ---- Droits ----

    /** Foyers ouverts a un utilisateur, dans l'ordre des foyers. */
    public List<Access> accesses(long userId) {
        return database.query(connection -> {
            Map<Long, Set<Long>> members = new LinkedHashMap<>();
            Sql.list(connection, "SELECT household_id, person_id FROM user_members WHERE user_id = ? ORDER BY person_id",
                    row -> members.computeIfAbsent(row.getLong(1), key -> new LinkedHashSet<>()).add(row.getLong(2)), userId);
            List<Access> result = new ArrayList<>();
            for (Access access : Sql.list(connection, """
                    SELECT a.household_id, h.name, a.whole_household, a.real_estate, a.manager FROM user_households a
                    JOIN households h ON h.id = a.household_id WHERE a.user_id = ? ORDER BY h.id""",
                    row -> new Access(userId, row.getLong(1), row.getString(2), row.getInt(3) == 1, Set.of(), row.getInt(4) == 1, row.getInt(5) == 1), userId)) {
                result.add(new Access(userId, access.householdId(), access.householdName(), access.wholeHousehold(),
                        Set.copyOf(members.getOrDefault(access.householdId(), Set.of())), access.realEstate(), access.manager()));
            }
            return result;
        });
    }

    /** Remplace les droits d'un utilisateur sur un foyer ; {@code null} retire l'acces. */
    public void setAccess(long userId, long householdId, Access access) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM user_households WHERE user_id = ? AND household_id = ?", userId, householdId);
            if (access != null) {
                Sql.update(connection, "INSERT INTO user_households (user_id, household_id, whole_household, real_estate, manager) VALUES (?, ?, ?, ?, ?)",
                        userId, householdId, access.wholeHousehold() ? 1 : 0, access.realEstate() ? 1 : 0, access.manager() ? 1 : 0);
                for (long person : access.members()) {
                    Sql.update(connection, "INSERT INTO user_members (user_id, household_id, person_id) VALUES (?, ?, ?)", userId, householdId, person);
                }
            }
            return null;
        });
    }

    /** Utilisateurs ayant acces a un foyer, avec leurs droits. */
    public Map<User, Access> accessesTo(long householdId) {
        Map<User, Access> result = new LinkedHashMap<>();
        for (User user : users()) {
            accesses(user.id()).stream().filter(access -> access.householdId() == householdId).findFirst().ifPresent(access -> result.put(user, access));
        }
        return result;
    }

    /** Gestionnaires actifs d'un foyer. */
    public long activeManagers(long householdId) {
        return accessesTo(householdId).entrySet().stream().filter(entry -> entry.getKey().active() && entry.getValue().manager()).count();
    }

    // ---- Accueil de la premiere connexion ----

    /** Accueil deja vu : par l'utilisateur, ou par l'administrateur ({@code userId} nul). */
    public boolean welcomed(Long userId) {
        return database.query(connection -> userId == null
                ? !Sql.list(connection, "SELECT value FROM settings WHERE key = 'admin_welcomed_on'", row -> row.getString(1)).isEmpty()
                : !Sql.list(connection, "SELECT id FROM users WHERE id = ? AND welcomed_on IS NOT NULL", row -> row.getLong(1), userId).isEmpty());
    }

    public void setWelcomed(Long userId, LocalDate today) {
        database.query(connection -> userId == null
                ? Sql.update(connection, "INSERT INTO settings (key, value) VALUES ('admin_welcomed_on', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", today)
                : Sql.update(connection, "UPDATE users SET welcomed_on = ? WHERE id = ?", today, userId));
    }

    // ---- Qui voit quoi : derniere lecture ----

    /** Moment ou l'utilisateur a lu les changements d'acces ; a defaut, la creation de son compte. */
    public java.time.LocalDateTime audienceSeen(long userId) {
        return database.query(connection -> Sql.list(connection, "SELECT audience_seen_at, created_on FROM users WHERE id = ?",
                row -> row.getString(1) != null ? java.time.LocalDateTime.parse(row.getString(1)) : LocalDate.parse(row.getString(2)).atStartOfDay(), userId))
                .stream().findFirst().orElse(java.time.LocalDateTime.MIN);
    }

    public void setAudienceSeen(long userId, java.time.LocalDateTime at) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET audience_seen_at = ? WHERE id = ?", at.toString(), userId));
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
                AccountRepository::auditEntry, limit));
    }

    /** Changements d'acces a un foyer apres ce moment, les plus recents d'abord. */
    public List<AuditEntry> accessChanges(long householdId, java.time.LocalDateTime after) {
        return database.query(connection -> Sql.list(connection, """
                SELECT at, actor, action, household_id, user_id, detail FROM audit_log
                WHERE household_id = ? AND action = 'ACCESS' AND at > ? ORDER BY at DESC, id DESC LIMIT 20""", AccountRepository::auditEntry, householdId, after.toString()));
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
}
