package fr.patria.budget;

import static fr.patria.storage.Sql.cents;

import fr.patria.common.Frequency;
import fr.patria.household.ShareRepository;
import fr.patria.household.Shares;
import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Depenses saisies a la main (table {@code budget_items}). */
public final class BudgetRepository {
    private final Database database;

    public BudgetRepository(Database database) {
        this.database = database;
    }

    public List<BudgetItem> all() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM budget_items ORDER BY category, label COLLATE NOCASE", BudgetRepository::map));
    }

    public Optional<BudgetItem> find(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM budget_items WHERE id = ?", BudgetRepository::map, id)).stream().findFirst();
    }

    public long insert(BudgetItem item) {
        return database.query(connection -> Sql.insert(connection,
                "INSERT INTO budget_items (label, category, amount_cents, frequency, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                item.label(), item.category().name(), cents(item.amount()), item.frequency().name(), item.start(), item.end()));
    }

    /** Depense a importer, avec sa repartition (vide = parts egales). */
    public record NewExpense(BudgetItem item, java.util.Map<Long, Integer> shares) {
    }

    /** Import : toutes les depenses et leurs repartitions sont enregistrees ensemble, ou aucune. */
    public int insertAll(List<NewExpense> rows) {
        return database.transaction(connection -> {
            int count = 0;
            for (NewExpense row : rows) {
                BudgetItem item = row.item();
                long id = Sql.insert(connection,
                        "INSERT INTO budget_items (label, category, amount_cents, frequency, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                        item.label(), item.category().name(), cents(item.amount()), item.frequency().name(), item.start(), item.end());
                if (!row.shares().isEmpty()) ShareRepository.write(connection, Shares.Subject.BUDGET_ITEM, id, row.shares());
                count++;
            }
            return count;
        });
    }

    public void update(BudgetItem item) {
        database.query(connection -> Sql.update(connection,
                "UPDATE budget_items SET label = ?, category = ?, amount_cents = ?, frequency = ?, start_date = ?, end_date = ? WHERE id = ?",
                item.label(), item.category().name(), cents(item.amount()), item.frequency().name(), item.start(), item.end(), item.id()));
    }

    public void delete(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM budget_items WHERE id = ?", id));
    }

    private static BudgetItem map(ResultSet row) throws SQLException {
        return new BudgetItem(row.getLong("id"), row.getString("label"), Category.valueOf(row.getString("category")), Sql.money(row, "amount_cents"),
                Frequency.valueOf(row.getString("frequency")), Sql.date(row, "start_date"), Sql.date(row, "end_date"));
    }
}
