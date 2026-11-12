// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.property;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Stockage des biens (table {@code properties}). La suppression d'un bien emporte ses prets et apports. */
public final class PropertyRepository {
    private final Database database;

    public PropertyRepository(Database database) {
        this.database = database;
    }

    public List<Property> all() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM properties ORDER BY name COLLATE NOCASE, id", PropertyRepository::map));
    }

    public Optional<Property> find(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM properties WHERE id = ?", PropertyRepository::map, id)).stream().findFirst();
    }

    public long insert(Property property) {
        long id = database.query(connection -> Sql.insert(connection, """
                INSERT INTO properties (name, kind, commune, address, area, purchase_cents, purchase_date, estimate_low_cents,
                    estimate_high_cents, estimate_date, sale_fees_rate, selected_value_cents)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", values(property)));
        recordEstimate(id, property);
        return id;
    }

    /** Historique des estimations : une ligne par date d'estimation (la derniere du jour l'emporte). */
    private void recordEstimate(long id, Property property) {
        if (property.estimateLow() == null && property.estimateHigh() == null) return;
        database.query(connection -> Sql.update(connection, """
                INSERT INTO property_estimates (property_id, recorded_on, low, high) VALUES (?, ?, ?, ?)
                ON CONFLICT(property_id, recorded_on) DO UPDATE SET low = excluded.low, high = excluded.high""",
                id, property.estimateDate(), cents(property.estimateLow()), cents(property.estimateHigh())));
    }

    /** Estimation d'un bien a une date : date, basse, haute. */
    public record Estimate(LocalDate on, BigDecimal low, BigDecimal high) {
    }

    /** Estimations successives d'un bien, de la plus ancienne a la plus recente. */
    public List<Estimate> estimates(long propertyId) {
        return database.query(connection -> Sql.list(connection, "SELECT recorded_on, low, high FROM property_estimates WHERE property_id = ? ORDER BY recorded_on",
                row -> new Estimate(LocalDate.parse(row.getString(1)), row.getObject(2) == null ? null : BigDecimal.valueOf(row.getLong(2), 2),
                        row.getObject(3) == null ? null : BigDecimal.valueOf(row.getLong(3), 2)), propertyId));
    }

    public void update(Property property) {
        Object[] values = values(property);
        Object[] parameters = java.util.Arrays.copyOf(values, values.length + 1);
        parameters[values.length] = property.id();
        database.query(connection -> Sql.update(connection, """
                UPDATE properties SET name = ?, kind = ?, commune = ?, address = ?, area = ?, purchase_cents = ?, purchase_date = ?,
                    estimate_low_cents = ?, estimate_high_cents = ?, estimate_date = ?, sale_fees_rate = ?, selected_value_cents = ?
                WHERE id = ?""", parameters));
        recordEstimate(property.id(), property);
    }

    public void updateSelectedValue(long id, BigDecimal value) {
        database.query(connection -> Sql.update(connection, "UPDATE properties SET selected_value_cents = ? WHERE id = ?", cents(value), id));
    }

    public void delete(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM properties WHERE id = ?", id));
    }

    private static Object[] values(Property property) {
        return new Object[] {property.name(), property.kind().name(), property.commune(), property.address(), property.area(),
                cents(property.purchase()), property.purchaseDate(), cents(property.estimateLow()), cents(property.estimateHigh()),
                property.estimateDate(), property.saleFeesRate(), cents(property.selectedValue())};
    }

    private static Property map(ResultSet row) throws SQLException {
        return new Property(row.getLong("id"), row.getString("name"), PropertyKind.valueOf(row.getString("kind")),
                row.getString("commune"), row.getString("address"), Sql.decimal(row, "area"),
                Sql.money(row, "purchase_cents"), Sql.date(row, "purchase_date"),
                Sql.money(row, "estimate_low_cents"), Sql.money(row, "estimate_high_cents"), Sql.date(row, "estimate_date"),
                Sql.decimal(row, "sale_fees_rate"), Sql.money(row, "selected_value_cents"));
    }
}
