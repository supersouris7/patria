package fr.patria.household;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repartitions enregistrees (table {@code shares}). Un element absent est partage a parts egales. */
public final class ShareRepository {
    private final Database database;

    public ShareRepository(Database database) {
        this.database = database;
    }

    private record Row(Shares.Key key, long personId, int percent) {
    }

    public Map<Shares.Key, Map<Long, Integer>> all() {
        List<Row> rows = database.query(connection -> Sql.list(connection, "SELECT * FROM shares ORDER BY subject, subject_id, person_id",
                row -> new Row(new Shares.Key(Shares.Subject.valueOf(row.getString("subject")), row.getLong("subject_id")), row.getLong("person_id"), row.getInt("percent"))));
        Map<Shares.Key, Map<Long, Integer>> result = new HashMap<>();
        for (Row row : rows) result.computeIfAbsent(row.key(), key -> new LinkedHashMap<>()).put(row.personId(), row.percent());
        return result;
    }

    public Map<Long, Integer> of(Shares.Subject subject, long id) {
        return all().getOrDefault(new Shares.Key(subject, id), Map.of());
    }

    /** Remplace la repartition ; une repartition vide revient aux parts egales. */
    public void set(Shares.Subject subject, long id, Map<Long, Integer> percents) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM shares WHERE subject = ? AND subject_id = ?", subject.name(), id);
            for (Map.Entry<Long, Integer> entry : percents.entrySet()) {
                Sql.update(connection, "INSERT INTO shares (subject, subject_id, person_id, percent) VALUES (?, ?, ?, ?)",
                        subject.name(), id, entry.getKey(), entry.getValue());
            }
            return null;
        });
    }

    public void delete(Shares.Subject subject, long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM shares WHERE subject = ? AND subject_id = ?", subject.name(), id));
    }
}
