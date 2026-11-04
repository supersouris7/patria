package fr.patria;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Deplace un bien d'un foyer a un autre : chaque foyer ayant sa base, le bien et tout ce qui en depend (prets,
 * echeanciers, apports, charges, occupation, prix du marche) sont recopies dans la base du foyer d'arrivee, avec de
 * nouveaux identifiants, puis supprimes de la base de depart. La repartition entre membres et le payeur de la taxe
 * fonciere ne suivent pas (les membres ne sont pas les memes) : le bien repart a « parts egales ».
 */
final class PropertyMove {
    private static final String PROPERTY_COLUMNS = "name, kind, commune, address, area, purchase_cents, purchase_date, estimate_low_cents, "
            + "estimate_high_cents, estimate_date, sale_fees_rate, selected_value_cents";

    private PropertyMove() {
    }

    /** Renvoie l'identifiant du bien dans le foyer d'arrivee. */
    static long move(Database source, Path sourceFile, Database target, long propertyId) {
        // ATTACH est interdit dans une transaction : la base de depart est attachee avant, puis la copie se fait d'un bloc.
        long moved = target.query(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '" + sourceFile.toAbsolutePath().toString().replace("'", "''") + "' AS src");
            }
            try {
                connection.setAutoCommit(false);
                try {
                    long copy = copy(connection, propertyId);
                    connection.commit();
                    return copy;
                } catch (SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DETACH DATABASE src");
                }
            }
        });
        source.transaction(connection -> {
            List<Long> loans = Sql.list(connection, "SELECT id FROM loans WHERE property_id = ?", row -> row.getLong(1), propertyId);
            for (long loan : loans) Sql.update(connection, "DELETE FROM shares WHERE subject = 'LOAN' AND subject_id = ?", loan);
            Sql.update(connection, "DELETE FROM shares WHERE subject = 'PROPERTY' AND subject_id = ?", propertyId);
            Sql.update(connection, "DELETE FROM properties WHERE id = ?", propertyId);
            return null;
        });
        return moved;
    }

    private static long copy(Connection connection, long propertyId) throws SQLException {
        long property = Sql.insert(connection, "INSERT INTO properties (" + PROPERTY_COLUMNS + ") SELECT " + PROPERTY_COLUMNS
                + " FROM src.properties WHERE id = ? RETURNING id", propertyId);
        for (long loan : Sql.list(connection, "SELECT id FROM src.loans WHERE property_id = ? ORDER BY id", row -> row.getLong(1), propertyId)) {
            long copy = Sql.insert(connection, "INSERT INTO loans (property_id, name) SELECT ?, name FROM src.loans WHERE id = ? RETURNING id", property, loan);
            Sql.update(connection, """
                    INSERT INTO schedule_lines (loan_id, due_date, type, amount_cents, principal_cents, interest_cents, fees_cents, remaining_cents, annual_rate)
                    SELECT ?, due_date, type, amount_cents, principal_cents, interest_cents, fees_cents, remaining_cents, annual_rate
                    FROM src.schedule_lines WHERE loan_id = ? ORDER BY id""", copy, loan);
        }
        Sql.update(connection, """
                INSERT INTO contributions (property_id, name, amount_cents, contribution_date)
                SELECT ?, name, amount_cents, contribution_date FROM src.contributions WHERE property_id = ? ORDER BY id""", property, propertyId);
        Sql.update(connection, """
                INSERT INTO expenses (property_id, kind, year, amount_cents)
                SELECT ?, kind, year, amount_cents FROM src.expenses WHERE property_id = ? ORDER BY id""", property, propertyId);
        Sql.update(connection, """
                INSERT INTO rents (property_id, label, start_date, end_date, monthly_cents, kind)
                SELECT ?, label, start_date, end_date, monthly_cents, kind FROM src.rents WHERE property_id = ? ORDER BY id""", property, propertyId);
        Sql.update(connection, "INSERT INTO property_tax_monthly (property_id) SELECT ? FROM src.property_tax_monthly WHERE property_id = ?", property, propertyId);
        Sql.update(connection, """
                INSERT INTO market_prices (property_id, fingerprint, computed_on, status, median_cents, low_cents, high_cents, sales_count, scope, place,
                    period, index_month, message, revaluation, latitude, longitude)
                SELECT ?, fingerprint, computed_on, status, median_cents, low_cents, high_cents, sales_count, scope, place,
                    period, index_month, message, revaluation, latitude, longitude FROM src.market_prices WHERE property_id = ?""", property, propertyId);
        Sql.update(connection, """
                INSERT INTO market_sales (property_id, sold_on, address, area, price_cents, per_sqm_cents, distance_m, latitude, longitude)
                SELECT ?, sold_on, address, area, price_cents, per_sqm_cents, distance_m, latitude, longitude
                FROM src.market_sales WHERE property_id = ? ORDER BY id""", property, propertyId);
        return property;
    }
}
