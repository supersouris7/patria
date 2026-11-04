package fr.patria.wizard;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Progression de l'assistant dans la base du foyer (tables {@code wizard_steps}, {@code wizard_documents}, migration
 * v13), par personne connectee : l'administrateur et chaque utilisateur ont la leur.
 */
public final class WizardRepository {
    /** Etat d'une etape. */
    enum Status { DONE, SKIPPED }

    /** Document rassemble, ou non concerne. */
    enum DocumentStatus { HAVE, NOT_CONCERNED }

    private final Database database;

    public WizardRepository(Database database) {
        this.database = database;
    }

    Map<Step, Status> steps(String owner) {
        Map<Step, Status> result = new HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT step, status FROM wizard_steps WHERE owner = ?", row -> {
            String step = row.getString(1);
            String status = row.getString(2);
            Step.of(step).ifPresent(known -> result.put(known, Status.valueOf(status)));
            return step;
        }, owner));
        return result;
    }

    void setStep(String owner, Step step, Status status, LocalDate today) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO wizard_steps (owner, step, status, updated_on) VALUES (?, ?, ?, ?)
                ON CONFLICT (owner, step) DO UPDATE SET status = excluded.status, updated_on = excluded.updated_on""", owner, step.name(), status.name(), today));
    }

    /** Utilisateur supprime : sa progression disparait. */
    public void forgetUser(long userId) {
        reset("user:" + userId);
    }

    /** Recommencer : efface la progression (les donnees saisies restent). */
    void reset(String owner) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM wizard_steps WHERE owner = ?", owner);
            Sql.update(connection, "DELETE FROM wizard_documents WHERE owner = ?", owner);
            return null;
        });
    }

    Map<String, DocumentStatus> documents(String owner) {
        Map<String, DocumentStatus> result = new HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT document, status FROM wizard_documents WHERE owner = ?", row -> {
            result.put(row.getString(1), DocumentStatus.valueOf(row.getString(2)));
            return null;
        }, owner));
        return result;
    }

    /** {@code null} : retire la coche. */
    void setDocument(String owner, String document, DocumentStatus status) {
        database.query(connection -> status == null
                ? Sql.update(connection, "DELETE FROM wizard_documents WHERE owner = ? AND document = ?", owner, document)
                : Sql.update(connection, """
                        INSERT INTO wizard_documents (owner, document, status) VALUES (?, ?, ?)
                        ON CONFLICT (owner, document) DO UPDATE SET status = excluded.status""", owner, document, status.name()));
    }
}
