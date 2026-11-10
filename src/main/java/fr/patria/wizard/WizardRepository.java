// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
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

    /** Ligne de la table des etapes qui garde la date de la derniere revue annuelle. */
    private static final String ANNUAL_REVIEW = "ANNUAL_REVIEW";
    /** Ligne qui note que l'encadre « configuration terminee a x % » a ete ignore. */
    private static final String BANNER_HIDDEN = "PROGRESS_BANNER_HIDDEN";

    /** Encadre de progression ignore (« Ignorer ») ; « Recommencer » le fait revenir. */
    boolean bannerHidden(String owner) {
        return !database.query(connection -> Sql.list(connection, "SELECT 1 FROM wizard_steps WHERE owner = ? AND step = ?", row -> row.getInt(1), owner, BANNER_HIDDEN)).isEmpty();
    }

    void hideBanner(String owner, LocalDate today) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO wizard_steps (owner, step, status, updated_on) VALUES (?, ?, 'DONE', ?)
                ON CONFLICT (owner, step) DO UPDATE SET updated_on = excluded.updated_on""", owner, BANNER_HIDDEN, today));
    }

    /** Date de la derniere revue annuelle terminee, {@code null} si aucune. */
    LocalDate reviewedOn(String owner) {
        List<String> dates = database.query(connection -> Sql.list(connection, "SELECT updated_on FROM wizard_steps WHERE owner = ? AND step = ?",
                row -> row.getString(1), owner, ANNUAL_REVIEW));
        return dates.isEmpty() ? null : LocalDate.parse(dates.getFirst());
    }

    void setReviewed(String owner, LocalDate today) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO wizard_steps (owner, step, status, updated_on) VALUES (?, ?, 'DONE', ?)
                ON CONFLICT (owner, step) DO UPDATE SET updated_on = excluded.updated_on""", owner, ANNUAL_REVIEW, today));
    }

    /** Premier passage dans le guide (plus ancienne etape faite ou passee), {@code null} si aucun. */
    LocalDate firstActivity(String owner) {
        List<String> dates = database.query(connection -> Sql.list(connection, "SELECT min(updated_on) FROM wizard_steps WHERE owner = ? AND step NOT IN (?, ?)",
                row -> row.getString(1), owner, ANNUAL_REVIEW, BANNER_HIDDEN));
        return dates.isEmpty() || dates.getFirst() == null ? null : LocalDate.parse(dates.getFirst());
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

    /** Avancement du guide d'une personne, sans aucune donnee saisie (page Foyers de l'administrateur). */
    public record Summary(int done, int skipped, int total, String next, LocalDate reviewedOn) {
        public int percent() {
            return total == 0 ? 100 : (done + skipped) * 100 / total;
        }
    }

    /** {@code userId} : le compte ; {@code realEstate} : etapes de l'immobilier ; {@code manager} : gestionnaire (membres). */
    public Summary summary(long userId, boolean realEstate, boolean manager) {
        String owner = "user:" + userId;
        List<Step> steps = Step.forViewer(realEstate, manager).stream().filter(step -> step != Step.SUMMARY).toList();
        Map<Step, Status> progress = steps(owner);
        int done = (int) steps.stream().filter(step -> progress.get(step) == Status.DONE).count();
        int skipped = (int) steps.stream().filter(step -> progress.get(step) == Status.SKIPPED).count();
        String next = steps.stream().filter(step -> !progress.containsKey(step)).map(Step::title).findFirst().orElse(null);
        return new Summary(done, skipped, steps.size(), next, reviewedOn(owner));
    }

}
