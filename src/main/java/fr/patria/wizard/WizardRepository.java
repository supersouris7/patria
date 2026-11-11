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

    /** Situation du foyer : une ligne « PROFILE » quand elle est repondue, puis une ligne par reponse « oui ». */
    private static final String PROFILE = "PROFILE";
    private static final List<String> PROFILE_FLAGS = List.of("COUPLE", "JOINT", "TENANT", "OWNER", "OTHER_PROPERTY", "SAVINGS");

    Profile profile(String owner) {
        java.util.Set<String> rows = new java.util.HashSet<>(database.query(connection -> Sql.list(connection,
                "SELECT step FROM wizard_steps WHERE owner = ? AND step LIKE 'PROFILE%'", row -> row.getString(1), owner)));
        if (!rows.contains(PROFILE)) return Profile.UNSET;
        java.util.function.Predicate<String> yes = flag -> rows.contains(PROFILE + "_" + flag);
        return new Profile(true, yes.test("COUPLE"), yes.test("JOINT"), yes.test("TENANT"), yes.test("OWNER"), yes.test("OTHER_PROPERTY"), yes.test("SAVINGS"));
    }

    void setProfile(String owner, Profile profile, LocalDate today) {
        boolean[] values = {profile.couple(), profile.joint(), profile.tenant(), profile.owner(), profile.otherProperty(), profile.savings()};
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM wizard_steps WHERE owner = ? AND step LIKE 'PROFILE%'", owner);
            Sql.update(connection, "INSERT INTO wizard_steps (owner, step, status, updated_on) VALUES (?, ?, 'DONE', ?)", owner, PROFILE, today);
            for (int index = 0; index < values.length; index++) {
                if (values[index]) Sql.update(connection, "INSERT INTO wizard_steps (owner, step, status, updated_on) VALUES (?, ?, 'DONE', ?)",
                        owner, PROFILE + "_" + PROFILE_FLAGS.get(index), today);
            }
            return null;
        });
    }

    /** Ligne de la table des etapes qui garde la date de la derniere revue annuelle. */
    private static final String ANNUAL_REVIEW = "ANNUAL_REVIEW";
    /** Ligne qui note que l'encadre « configuration terminee a x % » a ete ignore. */
    private static final String BANNER_HIDDEN = "PROGRESS_BANNER_HIDDEN";

    /** Encadre de progression ignore (« Ignorer ») ; « Recommencer » le fait revenir. */
    boolean bannerHidden(String owner) {
        return !database.query(connection -> Sql.list(connection, "SELECT 1 FROM wizard_steps WHERE owner = ? AND step = ?", row -> row.getInt(1), owner, BANNER_HIDDEN)).isEmpty();
    }

    /** Barre du guide masquee par ce compte. */
    public boolean guideHidden(long userId) {
        return bannerHidden("user:" + userId);
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
    /**
     * Avancement : etapes faites, a completer plus tard, total ; {@code next} : prochaine etape a faire ({@code null}
     * quand tout est fait), sa place dans le parcours, son adresse et les minutes restantes estimees.
     */
    public record Summary(int done, int skipped, int total, String next, LocalDate reviewedOn, int position, String nextPath, int minutes) {
        public int percent() {
            return total == 0 ? 100 : (done + skipped) * 100 / total;
        }

        /** Etat pour la barre « Guide de saisie » des pages (JSON) ; {@code null} quand il n'y a plus rien a faire. */
        public String json() {
            if (next == null) return "null";
            return "{\"step\":\"" + next.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"position\":" + position + ",\"total\":" + total
                    + ",\"path\":\"" + nextPath + "\",\"minutes\":" + minutes + "}";
        }
    }

    /** {@code userId} : le compte ; {@code realEstate} : etapes de l'immobilier ; {@code manager} : gestionnaire. */
    public Summary summary(long userId, boolean realEstate, boolean manager) {
        return summary("user:" + userId, realEstate, manager);
    }

    Summary summary(String owner, boolean realEstate, boolean manager) {
        List<Step> steps = Step.path(profile(owner), realEstate, manager).stream().filter(step -> step != Step.SUMMARY).toList();
        Map<Step, Status> progress = steps(owner);
        int done = (int) steps.stream().filter(step -> progress.get(step) == Status.DONE).count();
        int skipped = (int) steps.stream().filter(step -> progress.get(step) == Status.SKIPPED).count();
        Step next = steps.stream().filter(step -> !progress.containsKey(step)).findFirst().orElse(null);
        int minutes = steps.stream().filter(step -> !progress.containsKey(step)).mapToInt(Step::minutes).sum();
        return new Summary(done, skipped, steps.size(), next == null ? null : next.title(), reviewedOn(owner), next == null ? 0 : steps.indexOf(next) + 1,
                next == null ? null : next.path(), minutes);
    }
}
