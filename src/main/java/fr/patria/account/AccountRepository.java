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
    private static final int LATEST = 1;
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
            return Sql.insert(connection, "INSERT INTO users (login, name, password_hash, created_on) VALUES (?, ?, ?, ?) RETURNING id",
                    login, name, hash(password), today);
        });
    }

    public void updateUser(long id, String name, boolean active) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET name = ?, active = ? WHERE id = ?", name, active ? 1 : 0, id));
    }

    public void setPassword(long id, String password) {
        checkPassword(password);
        database.query(connection -> Sql.update(connection, "UPDATE users SET password_hash = ? WHERE id = ?", hash(password), id));
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
                    SELECT a.household_id, h.name, a.whole_household, a.real_estate FROM user_households a
                    JOIN households h ON h.id = a.household_id WHERE a.user_id = ? ORDER BY h.id""",
                    row -> new Access(userId, row.getLong(1), row.getString(2), row.getInt(3) == 1, Set.of(), row.getInt(4) == 1), userId)) {
                result.add(new Access(userId, access.householdId(), access.householdName(), access.wholeHousehold(),
                        Set.copyOf(members.getOrDefault(access.householdId(), Set.of())), access.realEstate()));
            }
            return result;
        });
    }

    /** Remplace les droits d'un utilisateur sur un foyer ; {@code null} retire l'acces. */
    public void setAccess(long userId, long householdId, Access access) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM user_households WHERE user_id = ? AND household_id = ?", userId, householdId);
            if (access != null) {
                Sql.update(connection, "INSERT INTO user_households (user_id, household_id, whole_household, real_estate) VALUES (?, ?, ?, ?)",
                        userId, householdId, access.wholeHousehold() ? 1 : 0, access.realEstate() ? 1 : 0);
                for (long person : access.members()) {
                    Sql.update(connection, "INSERT INTO user_members (user_id, household_id, person_id) VALUES (?, ?, ?)", userId, householdId, person);
                }
            }
            return null;
        });
    }
}
