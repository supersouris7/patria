package fr.patria.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneId.of("Europe/Paris"));

    @TempDir
    Path directory;

    /** Base telle que la creaient les versions anterieures au versionnement (montants REAL, colonnes ajoutees au fil de l'eau). */
    private Path legacyDatabase() throws Exception {
        Path file = directory.resolve("legacy.sqlite3");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE properties (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT NOT NULL, commune TEXT NOT NULL,
                        insee TEXT NOT NULL, area REAL NOT NULL, purchase REAL NOT NULL, purchase_date TEXT NOT NULL, estimate REAL NOT NULL,
                        estimate_date TEXT NOT NULL)""");
            statement.execute("""
                    CREATE TABLE loans (id INTEGER PRIMARY KEY AUTOINCREMENT, property_id INTEGER NOT NULL REFERENCES properties(id), name TEXT NOT NULL,
                        initial REAL NOT NULL, reference_balance REAL NOT NULL, reference_date TEXT NOT NULL, rate REAL NOT NULL,
                        duration_months INTEGER NOT NULL, start_date TEXT NOT NULL, due_day INTEGER NOT NULL, payment REAL NOT NULL, insurance REAL NOT NULL)""");
            statement.execute("""
                    CREATE TABLE loan_schedule_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, loan_id INTEGER NOT NULL, due_date TEXT NOT NULL,
                        type TEXT NOT NULL, amount REAL, principal_repaid REAL, interest REAL, fees REAL, remaining_principal REAL, annual_rate REAL)""");
            statement.execute("""
                    CREATE TABLE contributions (id INTEGER PRIMARY KEY AUTOINCREMENT, property_id INTEGER NOT NULL, name TEXT NOT NULL, amount REAL NOT NULL)""");
            statement.execute("INSERT INTO properties VALUES (1, 'Maison', 'Maison', 'Lyon', '', 95.5, 250000.1, '2020-01-01', 0, '2020-01-01')");
            statement.execute("INSERT INTO properties VALUES (2, 'Studio', 'Appartement', '', '', 0, 100000, '2021-01-01', 120000, '2024-01-01')");
            statement.execute("INSERT INTO loans VALUES (1, 1, 'Prêt', 200000, 0, '2020-01-01', 1.2, 240, '2020-01-01', 5, 900, 0)");
            statement.execute("INSERT INTO loans VALUES (2, 99, 'Orphelin', 1, 1, '2020-01-01', 1, 1, '2020-01-01', 1, 1, 0)");
            statement.execute("INSERT INTO loan_schedule_lines VALUES (1, 1, '2020-02-05', 'echeance', 900.07, 700.03, 200.04, NULL, 199299.97, 1.2)");
            statement.execute("INSERT INTO contributions VALUES (1, 2, 'Épargne', 15000.5)");
            statement.execute("ALTER TABLE properties ADD COLUMN recurring_fees_annual REAL NOT NULL DEFAULT 0");
            statement.execute("UPDATE properties SET recurring_fees_annual = 1850.4 WHERE id = 2");
        }
        return file;
    }

    @Test
    void migratesLegacyDatabaseToCentsAndBacksItUp() throws Exception {
        Path file = legacyDatabase();
        try (Database database = Database.open(file)) {
            Migrations.apply(database, CLOCK);

            database.query(connection -> {
                assertEquals(Migrations.LATEST, Migrations.userVersion(connection));
                try (Statement statement = connection.createStatement()) {
                    ResultSet house = statement.executeQuery("SELECT * FROM properties WHERE id = 1");
                    assertTrue(house.next());
                    assertEquals("HOUSE", house.getString("kind"));
                    assertEquals(25000010, house.getLong("purchase_cents"));
                    assertEquals("95.50", house.getString("area"));
                    house.getLong("estimate_low_cents");
                    assertTrue(house.wasNull(), "estimation à 0 devenue NULL");
                    assertEquals("0.080000", house.getString("sale_fees_rate"));

                    ResultSet line = statement.executeQuery("SELECT * FROM schedule_lines");
                    assertTrue(line.next());
                    assertEquals("ECHEANCE", line.getString("type"));
                    assertEquals(90007, line.getLong("amount_cents"));
                    assertEquals(19929997, line.getLong("remaining_cents"));
                    line.getLong("fees_cents");
                    assertTrue(line.wasNull());

                    ResultSet loans = statement.executeQuery("SELECT count(*) FROM loans");
                    assertEquals(1, loans.getInt(1), "le prêt orphelin est écarté");
                    ResultSet contribution = statement.executeQuery("SELECT amount_cents, contribution_date FROM contributions");
                    assertEquals(1500050, contribution.getLong(1));
                    assertEquals(10, contribution.getString(2).length());

                    ResultSet expense = statement.executeQuery("SELECT property_id, kind, year, amount_cents FROM expenses");
                    assertTrue(expense.next(), "ancien montant « frais récurrents » repris en charge");
                    assertEquals(2, expense.getLong(1));
                    assertEquals("OTHER", expense.getString(2));
                    assertEquals(2026, expense.getInt(3));
                    assertEquals(185040, expense.getLong(4));
                    statement.execute("INSERT INTO expenses (property_id, kind, year, amount_cents) VALUES (2, 'HOUSING_TAX', 2025, 50000)");
                    statement.execute("INSERT INTO rents (property_id, start_date, monthly_cents) VALUES (2, '2024-01-01', 90000)");
                    ResultSet rentKind = statement.executeQuery("SELECT kind FROM rents");
                    assertEquals("RENTAL", rentKind.getString(1), "v5 : période de location par défaut");
                    ResultSet column = statement.executeQuery("SELECT count(*) FROM pragma_table_info('properties') WHERE name = 'recurring_fees_cents'");
                    assertEquals(0, column.getInt(1), "colonne supprimée");

                    ResultSet revaluation = statement.executeQuery("SELECT count(*) FROM pragma_table_info('market_prices') WHERE name = 'revaluation'");
                    assertEquals(1, revaluation.getInt(1), "v6 : colonne d'actualisation");
                    ResultSet budgetTables = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('people', 'incomes', 'budget_items')");
                    assertEquals(3, budgetTables.getInt(1), "v7 : tables du budget");
                    ResultSet shareTable = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'shares'");
                    assertEquals(1, shareTable.getInt(1), "v8 : répartition entre les personnes");
                    statement.execute("INSERT INTO people (name) VALUES ('Camille')");
                    ResultSet kind = statement.executeQuery("SELECT kind FROM people");
                    assertEquals("PERSON", kind.getString(1), "v9 : membre de type personne par défaut");
                    ResultSet transfers = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'transfers'");
                    assertEquals(1, transfers.getInt(1), "v9 : virements");
                    ResultSet monthlyTax = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'property_tax_monthly'");
                    assertEquals(1, monthlyTax.getInt(1), "v10 : taxe foncière mensualisée");
                    ResultSet taxPayers = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'property_tax_payers'");
                    assertEquals(1, taxPayers.getInt(1), "v11 : payeur de la taxe foncière");

                    statement.execute("DELETE FROM properties WHERE id = 1");
                    ResultSet remaining = statement.executeQuery("SELECT count(*) FROM schedule_lines");
                    assertEquals(0, remaining.getInt(1), "suppression en cascade active après migration");
                }
                return null;
            });
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().equals("legacy.sqlite3.avant-migration-v0-20261102-110000")));
        }
    }

    @Test
    void freshDatabaseStartsAtLatestVersionWithoutBackup() throws Exception {
        try (Database database = Database.open(directory.resolve("new.sqlite3"))) {
            Migrations.apply(database, CLOCK);
            Migrations.apply(database, CLOCK);
            database.query(connection -> {
                assertEquals(Migrations.LATEST, Migrations.userVersion(connection));
                return null;
            });
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertNull(files.filter(path -> path.toString().contains("avant-migration")).findFirst().orElse(null));
        }
    }
}
