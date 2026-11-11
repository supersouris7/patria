// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
    private static final int LATEST = 7;
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
        if (version < 5) {
            // E-mail des comptes (confirme ou non), jetons de confirmation et de mot de passe oublie, proposition de la
            // double authentification ; e-mail et confirmation des demandes de compte. Rien n'est exige des comptes existants.
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN email TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE users ADD COLUMN must_confirm_email INTEGER NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE users ADD COLUMN offer_two_factor INTEGER NOT NULL DEFAULT 0");
                statement.execute("ALTER TABLE account_requests ADD COLUMN email TEXT");
                statement.execute("ALTER TABLE account_requests ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 1");
                statement.execute("ALTER TABLE account_requests ADD COLUMN token_hash TEXT");
                statement.execute("""
                        CREATE TABLE email_tokens (
                            token_hash TEXT PRIMARY KEY,
                            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            purpose TEXT NOT NULL,
                            email TEXT NOT NULL,
                            expires_at TEXT NOT NULL
                        )""");
                statement.execute("PRAGMA user_version = 5");
            }
        }
        if (version < 6) {
            // Les foyers se gerent eux-memes : invitations envoyees par un gestionnaire (ou par l'administrateur pour un
            // nouveau foyer ou un foyer sans gestionnaire). Seule l'empreinte du lien est gardee.
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE invitations (
                            id INTEGER PRIMARY KEY,
                            token_hash TEXT NOT NULL UNIQUE,
                            household_id INTEGER REFERENCES households(id) ON DELETE CASCADE,
                            member_id INTEGER,
                            member_name TEXT,
                            whole_household INTEGER NOT NULL,
                            real_estate INTEGER NOT NULL,
                            manager INTEGER NOT NULL,
                            email TEXT,
                            created_by TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            expires_at TEXT NOT NULL,
                            used_by INTEGER
                        )""");
                statement.execute("PRAGMA user_version = 6");
            }
        }
        if (version < 7) {
            // Profil du compte (prenom, nom, naissance, telephone, photo ronde), membre du budget qu'il represente dans
            // chaque foyer, et jetons d'API en lecture seule (tableaux de bord). Rien n'est retire ni reecrit.
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE users ADD COLUMN first_name TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN last_name TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN birth_date TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN phone TEXT");
                statement.execute("ALTER TABLE users ADD COLUMN avatar BLOB");
                statement.execute("ALTER TABLE user_households ADD COLUMN self_member INTEGER");
                statement.execute("""
                        CREATE TABLE api_tokens (
                            id INTEGER PRIMARY KEY,
                            user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
                            name TEXT NOT NULL,
                            token_hash TEXT NOT NULL UNIQUE,
                            created_at TEXT NOT NULL,
                            last_used_at TEXT
                        )""");
                statement.execute("PRAGMA user_version = 7");
            }
        }
    }

    // ---- Profil du compte ----

    /** Profil : prenom, nom, date de naissance, telephone ; {@code avatar} : une photo est enregistree. */
    public record Profile(String firstName, String lastName, LocalDate birthDate, String phone, boolean avatar) {
        public String displayName(String fallback) {
            String full = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).strip();
            return full.isEmpty() ? fallback : full;
        }
    }

    public Profile profile(long userId) {
        return database.query(connection -> Sql.list(connection, "SELECT first_name, last_name, birth_date, phone, avatar IS NOT NULL FROM users WHERE id = ?",
                row -> new Profile(row.getString(1), row.getString(2), row.getString(3) == null ? null : LocalDate.parse(row.getString(3)), row.getString(4),
                        row.getInt(5) == 1), userId)).stream().findFirst().orElse(new Profile(null, null, null, null, false));
    }

    /** Enregistre le profil ; le nom affiche devient « Prenom Nom » quand le prenom est donne. */
    public void setProfile(long userId, String firstName, String lastName, LocalDate birthDate, String phone) {
        String display = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).strip();
        database.query(connection -> Sql.update(connection, """
                UPDATE users SET first_name = ?, last_name = ?, birth_date = ?, phone = ?, name = CASE WHEN ? = '' THEN name ELSE ? END WHERE id = ?""",
                blank(firstName), blank(lastName), birthDate == null ? null : birthDate.toString(), blank(phone), display, display, userId));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public Optional<byte[]> avatar(long userId) {
        return database.query(connection -> Sql.list(connection, "SELECT avatar FROM users WHERE id = ? AND avatar IS NOT NULL", row -> row.getBytes(1), userId))
                .stream().findFirst();
    }

    /** {@code null} : retire la photo (les initiales reviennent). */
    public void setAvatar(long userId, byte[] image) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET avatar = ? WHERE id = ?", image, userId));
    }

    /** Comptes qui ont une photo (listes : photo ou initiales). */
    public java.util.Set<Long> usersWithAvatar() {
        return new java.util.HashSet<>(database.query(connection -> Sql.list(connection, "SELECT id FROM users WHERE avatar IS NOT NULL", row -> row.getLong(1))));
    }

    /** Les deux comptes partagent au moins un foyer (photo visible de l'un a l'autre). */
    public boolean shareHousehold(long userId, long otherId) {
        return userId == otherId || !database.query(connection -> Sql.list(connection, """
                SELECT 1 FROM user_households a JOIN user_households b ON a.household_id = b.household_id WHERE a.user_id = ? AND b.user_id = ? LIMIT 1""",
                row -> row.getInt(1), userId, otherId)).isEmpty();
    }

    /** Membre du budget que ce compte represente dans ce foyer ({@code null} : aucun). */
    public Long selfMember(long userId, long householdId) {
        return database.query(connection -> Sql.list(connection, "SELECT self_member FROM user_households WHERE user_id = ? AND household_id = ? AND self_member IS NOT NULL",
                row -> row.getLong(1), userId, householdId)).stream().findFirst().orElse(null);
    }

    public void setSelfMember(long userId, long householdId, Long member) {
        database.query(connection -> Sql.update(connection, "UPDATE user_households SET self_member = ? WHERE user_id = ? AND household_id = ?", member, userId, householdId));
    }

    /** Membres representes dans un foyer, par compte (tableau des droits). */
    public Map<Long, Long> selfMembers(long householdId) {
        Map<Long, Long> result = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT user_id, self_member FROM user_households WHERE household_id = ? AND self_member IS NOT NULL", row -> {
            result.put(row.getLong(1), row.getLong(2));
            return null;
        }, householdId));
        return result;
    }

    // ---- Jetons d'API (lecture seule) ----

    public record ApiToken(long id, long householdId, String householdName, String name, String createdAt, String lastUsedAt) {
    }

    /** Cree un jeton pour ce compte et ce foyer ; seul son empreinte est gardee : il ne s'affiche qu'une fois. */
    public String createApiToken(long userId, long householdId, String name, java.time.LocalDateTime now) {
        String token = "patria_" + randomToken();
        database.query(connection -> Sql.update(connection, "INSERT INTO api_tokens (user_id, household_id, name, token_hash, created_at) VALUES (?, ?, ?, ?, ?)",
                userId, householdId, name, sha256(token), now.withNano(0).toString()));
        return token;
    }

    public List<ApiToken> apiTokens(long userId) {
        return database.query(connection -> Sql.list(connection, """
                SELECT t.id, t.household_id, h.name, t.name, t.created_at, t.last_used_at FROM api_tokens t JOIN households h ON h.id = t.household_id
                WHERE t.user_id = ? ORDER BY t.id""",
                row -> new ApiToken(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5), row.getString(6)), userId));
    }

    public void revokeApiToken(long userId, long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM api_tokens WHERE id = ? AND user_id = ?", id, userId));
    }

    /** Compte et foyer d'un jeton valable (compte actif, toujours membre du foyer) ; note la derniere utilisation. */
    public Optional<long[]> apiToken(String token, java.time.LocalDateTime now) {
        if (token == null || !token.startsWith("patria_") || token.length() > 100) return Optional.empty();
        String hash = sha256(token);
        Optional<long[]> found = database.query(connection -> Sql.list(connection, """
                SELECT t.user_id, t.household_id FROM api_tokens t JOIN users u ON u.id = t.user_id AND u.active = 1
                JOIN user_households a ON a.user_id = t.user_id AND a.household_id = t.household_id WHERE t.token_hash = ?""",
                row -> new long[] {row.getLong(1), row.getLong(2)}, hash)).stream().findFirst();
        found.ifPresent(ids -> database.query(connection -> Sql.update(connection, "UPDATE api_tokens SET last_used_at = ? WHERE token_hash = ?",
                now.withNano(0).toString(), hash)));
        return found;
    }

    // ---- Foyers ----

    public List<Household> households() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM households ORDER BY id", AccountRepository::household));
    }

    /** Date de creation de chaque foyer (liste de l'administrateur). */
    public Map<Long, LocalDate> householdCreations() {
        Map<Long, LocalDate> created = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT id, created_on FROM households",
                row -> created.put(row.getLong(1), row.getString(2) == null ? null : LocalDate.parse(row.getString(2).substring(0, 10)))));
        return created;
    }

    /** Un compte vu par l'administrateur : date de creation et e-mail (jamais de donnee des foyers). */
    public record UserRow(User user, LocalDate createdOn, String email) {
    }

    public List<UserRow> userRows() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM users ORDER BY name COLLATE NOCASE, login", row -> new UserRow(user(row),
                row.getString("created_on") == null ? null : LocalDate.parse(row.getString("created_on").substring(0, 10)), row.getString("email"))));
    }

    /** Droits de tous les comptes, en deux requetes (liste de l'administrateur, quel que soit le nombre de comptes). */
    public Map<Long, List<Access>> allAccesses() {
        return database.query(connection -> {
            Map<String, Set<Long>> members = new java.util.HashMap<>();
            Sql.list(connection, "SELECT user_id, household_id, person_id FROM user_members",
                    row -> members.computeIfAbsent(row.getLong(1) + ":" + row.getLong(2), key -> new LinkedHashSet<>()).add(row.getLong(3)));
            Map<Long, List<Access>> result = new java.util.HashMap<>();
            Sql.list(connection, """
                    SELECT a.user_id, a.household_id, h.name, a.whole_household, a.real_estate, a.manager FROM user_households a
                    JOIN households h ON h.id = a.household_id ORDER BY h.id""", row -> result.computeIfAbsent(row.getLong(1), key -> new java.util.ArrayList<>())
                    .add(new Access(row.getLong(1), row.getLong(2), row.getString(3), row.getInt(4) == 1, members.getOrDefault(row.getLong(1) + ":" + row.getLong(2), Set.of()),
                            row.getInt(5) == 1, row.getInt(6) == 1)));
            return result;
        });
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

    // ---- E-mail des comptes ----

    /** E-mail d'un compte ; {@code verified} : confirme par le lien recu ; {@code required} : a confirmer avant tout. */
    public record Email(String address, boolean verified, boolean required) {
    }

    public Email email(long userId) {
        return database.query(connection -> Sql.list(connection, "SELECT email, email_verified, must_confirm_email FROM users WHERE id = ?",
                row -> new Email(row.getString(1), row.getInt(2) == 1, row.getInt(3) == 1), userId)).stream().findFirst().orElse(new Email(null, false, false));
    }

    /** Nouvel e-mail ; {@code verified} vrai seulement quand le lien de confirmation a ete suivi (ou sans envoi d'e-mail). */
    public void setEmail(long userId, String address, boolean verified) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET email = ?, email_verified = ?, must_confirm_email = CASE WHEN ? = 1 THEN 0 ELSE must_confirm_email END WHERE id = ?",
                address, verified ? 1 : 0, verified ? 1 : 0, userId));
    }

    /** Compte actif dont l'identifiant ou l'e-mail confirme correspond (mot de passe oublie). */
    public Optional<User> userForReset(String loginOrEmail) {
        String wanted = loginOrEmail == null ? "" : loginOrEmail.trim();
        return database.query(connection -> Sql.list(connection, """
                SELECT * FROM users WHERE active = 1 AND email_verified = 1 AND email IS NOT NULL AND (login = ? COLLATE NOCASE OR email = ? COLLATE NOCASE)""",
                AccountRepository::user, wanted, wanted)).stream().findFirst();
    }

    /** Jeton a usage unique envoye par e-mail ({@code purpose} : confirmation ou mot de passe) ; seule son empreinte est gardee. */
    public String createToken(long userId, String purpose, String email, java.time.LocalDateTime expiresAt) {
        String token = randomToken();
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM email_tokens WHERE user_id = ? AND purpose = ?", userId, purpose);
            Sql.update(connection, "INSERT INTO email_tokens (token_hash, user_id, purpose, email, expires_at) VALUES (?, ?, ?, ?, ?)", sha256(token), userId, purpose,
                    email, expiresAt.toString());
            return null;
        });
        return token;
    }

    /** Un jeton valide, consomme a la lecture : le compte et l'e-mail auquel il a ete envoye. */
    public record Token(long userId, String email) {
    }

    public Optional<Token> consumeToken(String token, String purpose, java.time.LocalDateTime now) {
        if (token == null || token.isBlank() || token.length() > 100) return Optional.empty();
        String hash = sha256(token);
        return database.transaction(connection -> {
            List<Token> found = Sql.list(connection, "SELECT user_id, email, expires_at FROM email_tokens WHERE token_hash = ? AND purpose = ?",
                    row -> java.time.LocalDateTime.parse(row.getString(3)).isAfter(now) ? new Token(row.getLong(1), row.getString(2)) : null, hash, purpose);
            Sql.update(connection, "DELETE FROM email_tokens WHERE token_hash = ?", hash);
            return found.stream().filter(java.util.Objects::nonNull).findFirst();
        });
    }

    /** Double authentification a proposer (compte cree depuis cette version, pas encore active ni « plus tard »). */
    public boolean offerTwoFactor(long userId) {
        return database.query(connection -> !Sql.list(connection, "SELECT 1 FROM users WHERE id = ? AND offer_two_factor = 1", row -> 1, userId).isEmpty());
    }

    public void stopOfferingTwoFactor(long userId) {
        database.query(connection -> Sql.update(connection, "UPDATE users SET offer_two_factor = 0 WHERE id = ?", userId));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
                    SELECT a.household_id, h.name, a.whole_household, a.real_estate, a.manager FROM user_households a
                    JOIN households h ON h.id = a.household_id WHERE a.user_id = ? ORDER BY h.id""",
                    row -> new Access(userId, row.getLong(1), row.getString(2), row.getInt(3) == 1, members.getOrDefault(row.getLong(1), Set.of()),
                            row.getInt(4) == 1, row.getInt(5) == 1), userId);
        });
    }

    /** Remplace les droits d'un utilisateur sur un foyer ; {@code null} ou aucune case cochee retire l'acces. */
    public void setAccess(long userId, long householdId, Access access) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM user_households WHERE user_id = ? AND household_id = ?", userId, householdId);
            if (access != null && (access.any() || access.manager())) {
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
        return database.query(connection -> {
            Map<Long, Set<Long>> members = new LinkedHashMap<>();
            Sql.list(connection, "SELECT user_id, person_id FROM user_members WHERE household_id = ? ORDER BY person_id",
                    row -> members.computeIfAbsent(row.getLong(1), key -> new LinkedHashSet<>()).add(row.getLong(2)), householdId);
            Map<User, Access> result = new LinkedHashMap<>();
            Sql.list(connection, """
                    SELECT u.*, a.whole_household, a.real_estate, a.manager, h.name AS household_name FROM user_households a
                    JOIN users u ON u.id = a.user_id JOIN households h ON h.id = a.household_id
                    WHERE a.household_id = ? ORDER BY u.name COLLATE NOCASE, u.login""", row -> {
                User user = user(row);
                return result.put(user, new Access(user.id(), householdId, row.getString("household_name"), row.getInt("whole_household") == 1,
                        members.getOrDefault(user.id(), Set.of()), row.getInt("real_estate") == 1, row.getInt("manager") == 1));
            }, householdId);
            return result;
        });
    }

    /** Un membre supprime n'est plus coche pour personne. */
    public void forgetMember(long householdId, long personId) {
        database.query(connection -> Sql.update(connection, "DELETE FROM user_members WHERE household_id = ? AND person_id = ?", householdId, personId));
    }

    /**
     * Passage aux foyers geres par leurs gestionnaires (une fois) : un compte qui voyait tout un foyer (tous ses
     * membres, les totaux et l'immobilier) en devient gestionnaire. Les autres droits ne changent pas.
     */
    public void promoteManagers(java.util.function.LongFunction<List<Long>> householdMembers) {
        if (!database.query(connection -> Sql.list(connection, "SELECT 1 FROM settings WHERE key = 'managers_promoted'", row -> 1)).isEmpty()) return;
        for (Household household : households()) {
            Set<Long> everyone = new LinkedHashSet<>(householdMembers.apply(household.id()));
            accessesTo(household.id()).forEach((user, access) -> {
                if (access.wholeHousehold() && access.realEstate() && access.members().containsAll(everyone)) {
                    database.query(connection -> Sql.update(connection, "UPDATE user_households SET manager = 1 WHERE user_id = ? AND household_id = ?",
                            user.id(), household.id()));
                }
            });
        }
        database.query(connection -> Sql.update(connection, "INSERT INTO settings (key, value) VALUES ('managers_promoted', 'on')"));
    }

    /** Le compte gere-t-il ce foyer (membres, droits, invitations) ? */
    public boolean isManager(long userId, long householdId) {
        return database.query(connection -> !Sql.list(connection, "SELECT 1 FROM user_households WHERE user_id = ? AND household_id = ? AND manager = 1",
                row -> 1, userId, householdId).isEmpty());
    }

    // ---- Invitations ----

    /**
     * Une invitation en attente : rejoindre {@code householdId} (ou creer son propre foyer si nul) avec ces droits ;
     * {@code memberId} : membre du foyer deja existant, sinon un membre {@code memberName} est cree a l'arrivee.
     */
    public record Invitation(long id, Long householdId, Long memberId, String memberName, boolean wholeHousehold, boolean realEstate, boolean manager,
                             String email, String createdBy, String expiresAt) {
    }

    /** Enregistre une invitation valable {@code days} jours ; renvoie le lien secret (seule son empreinte est gardee). */
    public String createInvitation(Invitation invitation, java.time.LocalDateTime now, int days) {
        String token = randomToken();
        database.query(connection -> Sql.insert(connection, """
                INSERT INTO invitations (token_hash, household_id, member_id, member_name, whole_household, real_estate, manager, email, created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", sha256(token), invitation.householdId(), invitation.memberId(), invitation.memberName(),
                invitation.wholeHousehold() ? 1 : 0, invitation.realEstate() ? 1 : 0, invitation.manager() ? 1 : 0, invitation.email(), invitation.createdBy(),
                now.toString(), now.plusDays(days).toString()));
        return token;
    }

    /** Invitation valide (non utilisee, non expiree) pour ce lien. */
    public Optional<Invitation> invitation(String token, java.time.LocalDateTime now) {
        if (token == null || token.isBlank() || token.length() > 100) return Optional.empty();
        return database.query(connection -> Sql.list(connection, "SELECT * FROM invitations WHERE token_hash = ? AND used_by IS NULL AND expires_at > ?",
                AccountRepository::invitation, sha256(token), now.toString())).stream().findFirst();
    }

    /** Invitations en attente d'un foyer ({@code null} : celles de l'administrateur pour de nouveaux foyers). */
    public List<Invitation> pendingInvitations(Long householdId, java.time.LocalDateTime now) {
        return database.query(connection -> householdId == null
                ? Sql.list(connection, "SELECT * FROM invitations WHERE household_id IS NULL AND used_by IS NULL AND expires_at > ? ORDER BY id", AccountRepository::invitation, now.toString())
                : Sql.list(connection, "SELECT * FROM invitations WHERE household_id = ? AND used_by IS NULL AND expires_at > ? ORDER BY id", AccountRepository::invitation,
                householdId, now.toString()));
    }

    /** Invitation utilisee (a usage unique) ; faux si elle l'etait deja. */
    public boolean useInvitation(long id, long userId) {
        return database.query(connection -> Sql.update(connection, "UPDATE invitations SET used_by = ? WHERE id = ? AND used_by IS NULL", userId, id)) > 0;
    }

    /** Annule une invitation en attente de ce foyer ({@code null} : invitation de l'administrateur). */
    public void cancelInvitation(long id, Long householdId) {
        database.query(connection -> householdId == null
                ? Sql.update(connection, "DELETE FROM invitations WHERE id = ? AND household_id IS NULL AND used_by IS NULL", id)
                : Sql.update(connection, "DELETE FROM invitations WHERE id = ? AND household_id = ? AND used_by IS NULL", id, householdId));
    }

    private static Invitation invitation(ResultSet row) throws SQLException {
        long household = row.getLong("household_id");
        Long householdId = row.wasNull() ? null : household;
        long member = row.getLong("member_id");
        Long memberId = row.wasNull() ? null : member;
        return new Invitation(row.getLong("id"), householdId, memberId, row.getString("member_name"), row.getInt("whole_household") == 1,
                row.getInt("real_estate") == 1, row.getInt("manager") == 1, row.getString("email"), row.getString("created_by"), row.getString("expires_at"));
    }

    /** Compte cree par la personne elle-meme (inscription, invitation) : mot de passe choisi, rien a changer. */
    public long registerUser(String login, String name, String password, LocalDate today, String email, boolean confirmEmail) {
        checkPassword(password);
        return database.transaction(connection -> {
            if (!Sql.list(connection, "SELECT id FROM users WHERE login = ? COLLATE NOCASE", row -> row.getLong(1), login).isEmpty()) {
                throw new ValidationException("L'identifiant « " + login + " » est déjà pris : choisissez-en un autre.");
            }
            return Sql.insert(connection, """
                    INSERT INTO users (login, name, password_hash, created_on, must_change_password, email, email_verified, must_confirm_email, offer_two_factor)
                    VALUES (?, ?, ?, ?, 0, ?, 0, ?, 1) RETURNING id""", login, name, hash(password), today, email, confirmEmail ? 1 : 0);
        });
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

    /** Reglages d'installation : type ({@code local} ou {@code cloud}, vide tant qu'il n'est pas choisi) et pages legales. */
    public record Installation(String mode, String terms, String notice) {
        public boolean cloud() {
            return "cloud".equals(mode);
        }
    }

    public Installation installation() {
        Map<String, String> values = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT key, value FROM settings WHERE key IN ('install_mode', 'legal_terms', 'legal_notice')",
                row -> values.put(row.getString(1), row.getString(2))));
        return new Installation(values.getOrDefault("install_mode", ""), values.getOrDefault("legal_terms", ""), values.getOrDefault("legal_notice", ""));
    }

    public void setInstallation(Installation installation) {
        database.transaction(connection -> {
            for (String[] pair : new String[][]{{"install_mode", installation.mode()}, {"legal_terms", installation.terms()}, {"legal_notice", installation.notice()}}) {
                Sql.update(connection, "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", pair[0], pair[1]);
            }
            return null;
        });
    }

    /**
     * Qui peut creer un compte : {@code open} (tout le monde, son propre foyer), {@code invite} (sur invitation d'un
     * gestionnaire ou de l'administrateur, par defaut) ou {@code closed} (personne). Les anciens reglages valent « invite ».
     */
    public String signupMode() {
        String mode = database.query(connection -> Sql.list(connection, "SELECT value FROM settings WHERE key = 'signup_mode'", row -> row.getString(1)))
                .stream().findFirst().orElse("invite");
        return mode.equals("open") || mode.equals("closed") ? mode : "invite";
    }

    public void setSignupMode(String mode) {
        database.query(connection -> Sql.update(connection,
                "INSERT INTO settings (key, value) VALUES ('signup_mode', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", mode));
    }

    /** Enregistre une demande ; refusee (faux) au-dela de {@code limit} demandes en attente ou si l'identifiant est deja demande. */
    // ---- Envoi d'e-mails (reglage de l'administrateur) ----

    /** Serveur d'envoi, adresse publique de Patria (liens des e-mails) et confirmation exigee des nouveaux comptes. */
    public record Mail(fr.patria.common.Smtp.Settings smtp, String publicUrl, boolean required) {
        public boolean active() {
            return smtp.configured() && !publicUrl.isBlank();
        }
    }

    public Mail mail() {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT key, value FROM settings WHERE key LIKE 'mail_%'", row -> values.put(row.getString(1), row.getString(2))));
        int port;
        try {
            port = Integer.parseInt(values.getOrDefault("mail_port", "587"));
        } catch (NumberFormatException exception) {
            port = 587;
        }
        return new Mail(new fr.patria.common.Smtp.Settings(values.getOrDefault("mail_host", ""), port, fr.patria.common.Smtp.security(values.get("mail_security")),
                values.getOrDefault("mail_user", ""), values.getOrDefault("mail_password", ""), values.getOrDefault("mail_from", "")),
                values.getOrDefault("mail_public_url", ""), "on".equals(values.get("mail_required")));
    }

    public void setMail(Mail mail) {
        database.transaction(connection -> {
            fr.patria.common.Smtp.Settings smtp = mail.smtp();
            for (String[] pair : new String[][]{{"mail_host", smtp.host()}, {"mail_port", String.valueOf(smtp.port())}, {"mail_security", smtp.security().name()},
                    {"mail_user", smtp.user()}, {"mail_password", smtp.password()}, {"mail_from", smtp.from()}, {"mail_public_url", mail.publicUrl()},
                    {"mail_required", mail.required() ? "on" : "off"}}) {
                Sql.update(connection, "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", pair[0], pair[1]);
            }
            return null;
        });
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
