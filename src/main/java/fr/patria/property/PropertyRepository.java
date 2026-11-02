package fr.patria.property;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        return database.query(connection -> Sql.insert(connection, """
                INSERT INTO properties (name, kind, commune, address, area, purchase_cents, purchase_date, estimate_low_cents,
                    estimate_high_cents, estimate_date, sale_fees_rate, selected_value_cents)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", values(property)));
    }

    public void update(Property property) {
        Object[] values = values(property);
        Object[] parameters = java.util.Arrays.copyOf(values, values.length + 1);
        parameters[values.length] = property.id();
        database.query(connection -> Sql.update(connection, """
                UPDATE properties SET name = ?, kind = ?, commune = ?, address = ?, area = ?, purchase_cents = ?, purchase_date = ?,
                    estimate_low_cents = ?, estimate_high_cents = ?, estimate_date = ?, sale_fees_rate = ?, selected_value_cents = ?
                WHERE id = ?""", parameters));
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
