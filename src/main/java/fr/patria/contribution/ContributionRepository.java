// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.contribution;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Stockage des apports (table {@code contributions}). */
public final class ContributionRepository {
    private final Database database;

    public ContributionRepository(Database database) {
        this.database = database;
    }

    public List<Contribution> all() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM contributions ORDER BY contribution_date DESC, id DESC", ContributionRepository::map));
    }

    public List<Contribution> forProperty(long propertyId) {
        return database.query(connection -> Sql.list(connection,
                "SELECT * FROM contributions WHERE property_id = ? ORDER BY contribution_date DESC, id DESC", ContributionRepository::map, propertyId));
    }

    public Optional<Contribution> find(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM contributions WHERE id = ?", ContributionRepository::map, id)).stream().findFirst();
    }

    public long insert(Contribution contribution) {
        return database.query(connection -> Sql.insert(connection,
                "INSERT INTO contributions (property_id, name, amount_cents, contribution_date) VALUES (?, ?, ?, ?) RETURNING id",
                contribution.propertyId(), contribution.name(), cents(contribution.amount()), contribution.date()));
    }

    public void update(Contribution contribution) {
        database.query(connection -> Sql.update(connection, "UPDATE contributions SET name = ?, amount_cents = ?, contribution_date = ? WHERE id = ?",
                contribution.name(), cents(contribution.amount()), contribution.date(), contribution.id()));
    }

    public void delete(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM contributions WHERE id = ?", id));
    }

    private static Contribution map(ResultSet row) throws SQLException {
        return new Contribution(row.getLong("id"), row.getLong("property_id"), row.getString("name"),
                Sql.money(row, "amount_cents"), Sql.date(row, "contribution_date"));
    }
}
