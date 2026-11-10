// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.expense;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Charges saisies (table {@code expenses}), une par type et par annee. */
public final class ExpenseRepository {
    private final Database database;

    public ExpenseRepository(Database database) {
        this.database = database;
    }

    public List<Expense> all() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM expenses ORDER BY year DESC, kind", ExpenseRepository::map));
    }

    public List<Expense> forProperty(long propertyId) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM expenses WHERE property_id = ? ORDER BY year DESC, kind", ExpenseRepository::map, propertyId));
    }

    public Optional<Expense> find(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM expenses WHERE id = ?", ExpenseRepository::map, id)).stream().findFirst();
    }

    /** Enregistre le montant d'une annee ; remplace celui deja saisi pour ce type et cette annee. */
    public void save(Expense expense) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO expenses (property_id, kind, year, amount_cents) VALUES (?, ?, ?, ?)
                ON CONFLICT (property_id, kind, year) DO UPDATE SET amount_cents = excluded.amount_cents""",
                expense.propertyId(), expense.kind().name(), expense.year(), cents(expense.amount())));
    }

    public void delete(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM expenses WHERE id = ?", id));
    }

    /** Biens dont la taxe fonciere est mensualisee. */
    public Set<Long> monthlyPropertyTax() {
        List<Long> ids = database.query(connection -> Sql.list(connection, "SELECT property_id FROM property_tax_monthly", row -> row.getLong(1)));
        return new TreeSet<>(ids);
    }

    public boolean isPropertyTaxMonthly(long propertyId) {
        return monthlyPropertyTax().contains(propertyId);
    }

    public void setPropertyTaxMonthly(long propertyId, boolean monthly) {
        database.query(connection -> monthly
                ? Sql.update(connection, "INSERT OR IGNORE INTO property_tax_monthly (property_id) VALUES (?)", propertyId)
                : Sql.update(connection, "DELETE FROM property_tax_monthly WHERE property_id = ?", propertyId));
    }

    private static Expense map(ResultSet row) throws SQLException {
        return new Expense(row.getLong("id"), row.getLong("property_id"), ExpenseKind.valueOf(row.getString("kind")),
                row.getInt("year"), Sql.money(row, "amount_cents"));
    }
}
