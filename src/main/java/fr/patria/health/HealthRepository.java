package fr.patria.health;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.util.HashSet;
import java.util.Set;

/** Choix personnels du bilan, isoles dans la base du foyer. Aucun chiffre n'est enregistre. */
public final class HealthRepository {
    private final Database database;

    public HealthRepository(Database database) {
        this.database = database;
    }

    Set<String> ignored(long userId) {
        return new HashSet<>(database.query(connection -> Sql.list(connection,
                "SELECT check_token FROM health_ignored WHERE user_id = ?", row -> row.getString(1), userId)));
    }

    void ignore(long userId, String token) {
        database.query(connection -> Sql.update(connection,
                "INSERT OR IGNORE INTO health_ignored (user_id, check_token) VALUES (?, ?)", userId, token));
    }

    void restore(long userId, String token) {
        database.query(connection -> Sql.update(connection,
                "DELETE FROM health_ignored WHERE user_id = ? AND check_token = ?", userId, token));
    }

    public void forgetUser(long userId) {
        database.query(connection -> Sql.update(connection, "DELETE FROM health_ignored WHERE user_id = ?", userId));
    }
}
