package fr.patria.account;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.time.LocalDate;
import java.util.Map;

/**
 * Double authentification (TOTP) de chaque compte et appareils retenus 30 jours. Tables creees par
 * {@link AccountRepository} (migration v3).
 */
public final class TwoFactorStore {
    private final Database database;

    public TwoFactorStore(Database database) {
        this.database = database;
    }

    // ---- Double authentification ----

    /** Cle du compte dans les tables de la double authentification : « admin » ou « user:ID ». */
    public static String account(Long userId) {
        return userId == null ? "admin" : "user:" + userId;
    }

    /** Etat de la double authentification d'un compte. */
    public record TwoFactor(String secret, String pending, LocalDate enabledOn, long lastStep, LocalDate removedByAdminOn) {
        public boolean enabled() {
            return secret != null;
        }
    }

    public TwoFactor twoFactor(Long userId) {
        return database.query(connection -> Sql.list(connection, "SELECT secret, pending, enabled_on, last_step, removed_by_admin_on FROM two_factor WHERE account = ?",
                row -> new TwoFactor(row.getString(1), row.getString(2), row.getString(3) == null ? null : LocalDate.parse(row.getString(3)), row.getLong(4),
                        row.getString(5) == null ? null : LocalDate.parse(row.getString(5))), account(userId)))
                .stream().findFirst().orElse(new TwoFactor(null, null, null, -1, null));
    }

    /** Secret propose a l'activation, en attente du premier code. */
    public void setTwoFactorPending(Long userId, String secret) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO two_factor (account, pending) VALUES (?, ?) ON CONFLICT (account) DO UPDATE SET pending = excluded.pending""", account(userId), secret));
    }

    /** Premier code juste : le secret en attente devient celui du compte. */
    public void enableTwoFactor(Long userId, long step, LocalDate today) {
        database.query(connection -> Sql.update(connection, """
                UPDATE two_factor SET secret = pending, pending = NULL, enabled_on = ?, last_step = ?, removed_by_admin_on = NULL WHERE account = ?""",
                today, step, account(userId)));
    }

    /** Code accepte : il ne pourra plus servir. */
    public void useTwoFactorStep(Long userId, long step) {
        database.query(connection -> Sql.update(connection, "UPDATE two_factor SET last_step = ? WHERE account = ?", step, account(userId)));
    }

    /**
     * Desactivation (par le compte lui-meme, ou {@code byAdminOn} : par l'administrateur, date retenue pour en
     * informer le compte) ; les appareils de confiance sont oublies.
     */
    public void disableTwoFactor(Long userId, LocalDate byAdminOn) {
        database.transaction(connection -> {
            Sql.update(connection, """
                    INSERT INTO two_factor (account, removed_by_admin_on) VALUES (?, ?)
                    ON CONFLICT (account) DO UPDATE SET secret = NULL, pending = NULL, enabled_on = NULL, last_step = -1, removed_by_admin_on = excluded.removed_by_admin_on""",
                    account(userId), byAdminOn);
            Sql.update(connection, "DELETE FROM trusted_devices WHERE account = ?", account(userId));
            return null;
        });
    }

    /** L'avis « l'administrateur a retire votre double authentification » a ete lu. */
    public void clearTwoFactorNotice(Long userId) {
        database.query(connection -> Sql.update(connection, "UPDATE two_factor SET removed_by_admin_on = NULL WHERE account = ?", account(userId)));
    }

    public void trustDevice(Long userId, String tokenHash, java.time.LocalDateTime now, java.time.LocalDateTime expires) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM trusted_devices WHERE expires_at < ?", now.toString());
            Sql.update(connection, "INSERT INTO trusted_devices (token_hash, account, expires_at) VALUES (?, ?, ?)", tokenHash, account(userId), expires.toString());
            return null;
        });
    }

    public boolean trustedDevice(Long userId, String tokenHash, java.time.LocalDateTime now) {
        return !database.query(connection -> Sql.list(connection, "SELECT 1 FROM trusted_devices WHERE token_hash = ? AND account = ? AND expires_at > ?",
                row -> row.getInt(1), tokenHash, account(userId), now.toString())).isEmpty();
    }

    /** Comptes ({@code null} : l'administrateur) qui ont la double authentification, avec sa date d'activation. */
    public Map<String, LocalDate> twoFactorAccounts() {
        Map<String, LocalDate> result = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT account, enabled_on FROM two_factor WHERE secret IS NOT NULL",
                row -> result.put(row.getString(1), row.getString(2) == null ? LocalDate.MIN : LocalDate.parse(row.getString(2)))));
        return result;
    }

    /** Compte supprime : sa double authentification et ses appareils disparaissent. */
    public void forget(long userId) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM two_factor WHERE account = ?", account(userId));
            Sql.update(connection, "DELETE FROM trusted_devices WHERE account = ?", account(userId));
            return null;
        });
    }
}
