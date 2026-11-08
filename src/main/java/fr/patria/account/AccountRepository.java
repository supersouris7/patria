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
    private static final int LATEST = 4;
    /** Hache de reference : un identifiant inconnu coute le meme temps qu'un mauvais mot de passe. */
    /** Cout de bcrypt : 12 ; la propriete systeme {@code patria.bcrypt.cost} l'abaisse pour les tests (4 a 31). */
    private static final int COST = Math.clamp(Integer.getInteger("patria.bcrypt.cost", 12), 4, 31);
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
            // Gestionnaires (role retire, voir retireManagers) ; accueil (welcomed_on, settings pour l'administrateur) ; mot de passe choisi par
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
        if (version < 3) {
            // Double authentification (TOTP) par compte (« admin » ou « user:ID ») et appareils de confiance (30 jours).
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE two_factor (
                            account TEXT PRIMARY KEY,
                            secret TEXT,
                            pending TEXT,
                            enabled_on TEXT,
                            last_step INTEGER NOT NULL DEFAULT -1,
                            removed_by_admin_on TEXT
                        )""");
                statement.execute("CREATE TABLE trusted_devices (token_hash TEXT PRIMARY KEY, account TEXT NOT NULL, expires_at TEXT NOT NULL)");
                statement.execute("PRAGMA user_version = 3");
            }
        }
        if (version < 4) {
            // Demandes de creation de compte envoyees depuis la page de connexion.
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE account_requests (
                            id INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            login TEXT NOT NULL,
                            message TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )""");
                statement.execute("PRAGMA user_version = 4");
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

    public boolean loginTaken(String login) {
        return database.query(connection -> !Sql.list(connection, "SELECT id FROM users WHERE login = ? COLLATE NOCASE", row -> row.getLong(1), login).isEmpty());
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
            return Sql.list(connection, """
                    SELECT a.household_id, h.name, a.whole_household, a.real_estate FROM user_households a
                    JOIN households h ON h.id = a.household_id WHERE a.user_id = ? ORDER BY h.id""",
                    row -> new Access(userId, row.getLong(1), row.getString(2), row.getInt(3) == 1, members.getOrDefault(row.getLong(1), Set.of()),
                            row.getInt(4) == 1), userId);
        });
    }

    /** Remplace les droits d'un utilisateur sur un foyer ; {@code null} ou aucune case cochee retire l'acces. */
    public void setAccess(long userId, long householdId, Access access) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM user_households WHERE user_id = ? AND household_id = ?", userId, householdId);
            if (access != null && access.any()) {
                Sql.update(connection, "INSERT INTO user_households (user_id, household_id, whole_household, real_estate) VALUES (?, ?, ?, ?)",
                        userId, householdId, access.wholeHousehold() ? 1 : 0, access.realEstate() ? 1 : 0);
                for (long person : access.members()) {
                    Sql.update(connection, "INSERT INTO user_members (user_id, household_id, person_id) VALUES (?, ?, ?)", userId, householdId, person);
                }
            }
            return null;
        });
    }

    /** Utilisateurs ayant acces a un foyer, avec leurs droits. */
    public Map<User, Access> accessesTo(long householdId) {
        return database.query(connection -> {
            Map<Long, Set<Long>> members = new LinkedHashMap<>();
            Sql.list(connection, "SELECT user_id, person_id FROM user_members WHERE household_id = ? ORDER BY person_id",
                    row -> members.computeIfAbsent(row.getLong(1), key -> new LinkedHashSet<>()).add(row.getLong(2)), householdId);
            Map<User, Access> result = new LinkedHashMap<>();
            Sql.list(connection, """
                    SELECT u.*, a.whole_household, a.real_estate, h.name AS household_name FROM user_households a
                    JOIN users u ON u.id = a.user_id JOIN households h ON h.id = a.household_id
                    WHERE a.household_id = ? ORDER BY u.name COLLATE NOCASE, u.login""", row -> {
                User user = user(row);
                return result.put(user, new Access(user.id(), householdId, row.getString("household_name"), row.getInt("whole_household") == 1,
                        members.getOrDefault(user.id(), Set.of()), row.getInt("real_estate") == 1));
            }, householdId);
            return result;
        });
    }

    /** Un membre supprime n'est plus coche pour personne. */
    public void forgetMember(long householdId, long personId) {
        database.query(connection -> Sql.update(connection, "DELETE FROM user_members WHERE household_id = ? AND person_id = ?", householdId, personId));
    }

    /**
     * Le role de gestionnaire n'existe plus : un ancien gestionnaire garde exactement ce qu'il voyait (tous les membres,
     * les totaux et l'immobilier), en cases cochees. Appele au demarrage ; sans effet ensuite.
     */
    public void retireManagers(java.util.function.LongFunction<List<Long>> householdMembers) {
        List<long[]> managers = database.query(connection -> Sql.list(connection, "SELECT user_id, household_id FROM user_households WHERE manager = 1",
                row -> new long[]{row.getLong(1), row.getLong(2)}));
        for (long[] manager : managers) {
            List<Long> people = householdMembers.apply(manager[1]);
            database.transaction(connection -> {
                Sql.update(connection, "UPDATE user_households SET manager = 0, whole_household = 1, real_estate = 1 WHERE user_id = ? AND household_id = ?",
                        manager[0], manager[1]);
                for (long person : people) {
                    Sql.update(connection, "INSERT OR IGNORE INTO user_members (user_id, household_id, person_id) VALUES (?, ?, ?)", manager[0], manager[1], person);
                }
                return null;
            });
        }
    }

    // ---- Demandes de compte ----

    /** Une demande envoyee depuis la page de connexion. */
    public record AccountRequest(long id, String name, String login, String message, String createdAt) {
    }

    /** Ce que la page de connexion propose : {@code "message"} (demander a l'administrateur) ou {@code "form"}. */
    /** IA locale reglee par l'administrateur (desactivee par defaut). */
    public fr.patria.ai.LocalAi.Settings localAi() {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT key, value FROM settings WHERE key LIKE 'local_ai_%'", row -> values.put(row.getString(1), row.getString(2))));
        try {
            return new fr.patria.ai.LocalAi.Settings("on".equals(values.get("local_ai_enabled")), values.get("local_ai_url"), values.get("local_ai_model"));
        } catch (fr.patria.common.ValidationException exception) {
            return fr.patria.ai.LocalAi.Settings.OFF;
        }
    }

    public void setLocalAi(fr.patria.ai.LocalAi.Settings settings) {
        database.transaction(connection -> {
            for (String[] pair : new String[][]{{"local_ai_enabled", settings.enabled() ? "on" : "off"}, {"local_ai_url", settings.url()}, {"local_ai_model", settings.model()}}) {
                Sql.update(connection, "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", pair[0], pair[1]);
            }
            return null;
        });
    }

    public String signupMode() {
        return database.query(connection -> Sql.list(connection, "SELECT value FROM settings WHERE key = 'signup_mode'", row -> row.getString(1)))
                .stream().findFirst().orElse("message");
    }

    public void setSignupMode(String mode) {
        database.query(connection -> Sql.update(connection,
                "INSERT INTO settings (key, value) VALUES ('signup_mode', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", mode));
    }

    public List<AccountRequest> requests() {
        return database.query(connection -> Sql.list(connection, "SELECT id, name, login, message, created_at FROM account_requests ORDER BY id",
                row -> new AccountRequest(row.getLong(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5))));
    }

    /** Enregistre une demande ; refusee (faux) au-dela de {@code limit} demandes en attente ou si l'identifiant est deja demande. */
    public boolean addRequest(String name, String login, String message, String createdAt, int limit) {
        return database.transaction(connection -> {
            long pending = Sql.list(connection, "SELECT COUNT(*) FROM account_requests", row -> row.getLong(1)).getFirst();
            boolean taken = !Sql.list(connection, "SELECT 1 FROM account_requests WHERE login = ? COLLATE NOCASE", row -> 1, login).isEmpty();
            if (pending >= limit || taken) return false;
            Sql.update(connection, "INSERT INTO account_requests (name, login, message, created_at) VALUES (?, ?, ?, ?)", name, login, message, createdAt);
            return true;
        });
    }

    public void deleteRequest(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM account_requests WHERE id = ?", id));
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
    /**
     * Ce qu'il faut savoir d'un compte a chaque page, lu en une requete : le compte, mot de passe provisoire, derniere
     * lecture de « qui voit quoi » et date du retrait de sa double authentification par l'administrateur.
     */
    public record AccountState(User user, boolean mustChangePassword, java.time.LocalDateTime audienceSeen, LocalDate twoFactorRemovedOn) {
    }

    public Optional<AccountState> state(long userId) {
        return database.query(connection -> Sql.list(connection, """
                SELECT u.*, coalesce(u.audience_seen_at, u.created_on || 'T00:00') AS seen, t.removed_by_admin_on FROM users u
                LEFT JOIN two_factor t ON t.account = 'user:' || u.id WHERE u.id = ?""",
                row -> new AccountState(user(row), row.getInt("must_change_password") == 1, java.time.LocalDateTime.parse(row.getString("seen")),
                        row.getString("removed_by_admin_on") == null ? null : LocalDate.parse(row.getString("removed_by_admin_on"))), userId)).stream().findFirst();
    }

    public java.time.LocalDateTime audienceSeen(long userId) {
        return database.query(connection -> Sql.list(connection, "SELECT audience_seen_at, created_on FROM users WHERE id = ?",
                row -> row.getString(1) != null ? java.time.LocalDateTime.parse(row.getString(1)) : LocalDate.parse(row.getString(2)).atStartOfDay(), userId))
                .stream().findFirst().orElse(java.time.LocalDateTime.MIN);
    }

    public void setAudienceSeen(long userId, java.time.LocalDateTime at) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET audience_seen_at = ? WHERE id = ?", at.toString(), userId));
    }
}
