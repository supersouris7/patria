// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.rent;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Periodes d'occupation : locations et residence principale (table {@code rents}). */
public final class RentRepository {
    private final Database database;

    public RentRepository(Database database) {
        this.database = database;
    }

    public List<RentPeriod> all() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM rents ORDER BY start_date DESC, id DESC", RentRepository::map));
    }

    public List<RentPeriod> forProperty(long propertyId) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM rents WHERE property_id = ? ORDER BY start_date DESC, id DESC", RentRepository::map, propertyId));
    }

    public Optional<RentPeriod> find(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM rents WHERE id = ?", RentRepository::map, id)).stream().findFirst();
    }

    public long insert(RentPeriod period) {
        return database.query(connection -> Sql.insert(connection,
                "INSERT INTO rents (property_id, kind, label, start_date, end_date, monthly_cents) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                period.propertyId(), period.kind().name(), period.label(), period.start(), period.end(), cents(period.monthly())));
    }

    public void update(RentPeriod period) {
        database.query(connection -> Sql.update(connection, "UPDATE rents SET kind = ?, label = ?, start_date = ?, end_date = ?, monthly_cents = ? WHERE id = ?",
                period.kind().name(), period.label(), period.start(), period.end(), cents(period.monthly()), period.id()));
    }

    public void delete(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM rents WHERE id = ?", id));
    }

    private static RentPeriod map(ResultSet row) throws SQLException {
        return new RentPeriod(row.getLong("id"), row.getLong("property_id"), Occupancy.valueOf(row.getString("kind")), row.getString("label"), Sql.date(row, "start_date"),
                Sql.date(row, "end_date"), Sql.money(row, "monthly_cents"));
    }
}
