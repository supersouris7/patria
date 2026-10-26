package fr.patria;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class Database {
    private final String url;

    Database(Path path) {
        this.url = "jdbc:sqlite:" + path.toAbsolutePath();
    }

    synchronized void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Pilote SQLite introuvable.", exception);
        }
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS properties (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                        kind TEXT NOT NULL, commune TEXT NOT NULL, address TEXT NOT NULL DEFAULT '', insee TEXT NOT NULL,
                        area REAL NOT NULL, purchase REAL NOT NULL, purchase_date TEXT NOT NULL,
                        estimate REAL NOT NULL, estimate_high REAL NOT NULL DEFAULT 0, estimate_date TEXT NOT NULL
                    )
                    """);
            try { statement.execute("ALTER TABLE properties ADD COLUMN address TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) { }
            try { statement.execute("ALTER TABLE properties ADD COLUMN estimate_high REAL NOT NULL DEFAULT 0"); statement.execute("UPDATE properties SET estimate_high = estimate WHERE estimate_high = 0"); } catch (SQLException ignored) { }
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS loans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, property_id INTEGER NOT NULL REFERENCES properties(id),
                        name TEXT NOT NULL, initial REAL NOT NULL, reference_balance REAL NOT NULL,
                        reference_date TEXT NOT NULL, rate REAL NOT NULL, duration_months INTEGER NOT NULL,
                        start_date TEXT NOT NULL, due_day INTEGER NOT NULL, payment REAL NOT NULL, insurance REAL NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS loan_schedule_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, loan_id INTEGER NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
                        due_date TEXT NOT NULL, type TEXT NOT NULL, amount REAL, principal_repaid REAL,
                        interest REAL, fees REAL, remaining_principal REAL, annual_rate REAL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS contributions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
                        name TEXT NOT NULL, amount REAL NOT NULL, contribution_date TEXT NOT NULL DEFAULT ''
                    )
                    """);
            try { statement.execute("ALTER TABLE contributions ADD COLUMN contribution_date TEXT NOT NULL DEFAULT ''"); } catch (SQLException ignored) { }
            statement.execute("UPDATE contributions SET contribution_date = date('now') WHERE contribution_date = ''");
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'initialiser SQLite.", exception);
        }
    }

    synchronized List<Property> properties() {
        List<Property> results = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM properties ORDER BY id DESC"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                results.add(new Property(rows.getLong("id"), rows.getString("name"), rows.getString("kind"),
                        rows.getString("commune"), rows.getString("address"), rows.getString("insee"), rows.getBigDecimal("area"),
                        rows.getBigDecimal("purchase"), LocalDate.parse(rows.getString("purchase_date")),
                        rows.getBigDecimal("estimate"), rows.getBigDecimal("estimate_high"), LocalDate.parse(rows.getString("estimate_date"))));
            }
            return results;
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de lire les biens.", exception);
        }
    }

    synchronized List<Loan> loans() {
        List<Loan> results = new ArrayList<>();
        String query = "SELECT loans.*, properties.name AS property_name FROM loans JOIN properties ON properties.id = loans.property_id ORDER BY loans.id DESC";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(query); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                results.add(new Loan(rows.getLong("id"), rows.getLong("property_id"), rows.getString("property_name"),
                        rows.getString("name"), rows.getBigDecimal("initial"), rows.getBigDecimal("reference_balance"),
                        LocalDate.parse(rows.getString("reference_date")), rows.getBigDecimal("rate"),
                        rows.getInt("duration_months"), LocalDate.parse(rows.getString("start_date")), rows.getInt("due_day"),
                        rows.getBigDecimal("payment"), rows.getBigDecimal("insurance")));
            }
            return results;
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de lire les prets.", exception);
        }
    }

    synchronized Property property(long id) {
        return properties().stream().filter(property -> property.id() == id).findFirst().orElse(null);
    }

    synchronized List<Loan> loansForProperty(long propertyId) {
        return loans().stream().filter(loan -> loan.propertyId() == propertyId).toList();
    }

    synchronized List<Contribution> contributions(long propertyId) {
        List<Contribution> results = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM contributions WHERE property_id = ? ORDER BY id DESC")) {
            statement.setLong(1, propertyId);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) results.add(new Contribution(rows.getLong("id"), propertyId, rows.getString("name"), rows.getBigDecimal("amount"), LocalDate.parse(rows.getString("contribution_date")))); }
            return results;
        } catch (SQLException exception) { throw new IllegalStateException("Impossible de lire les apports.", exception); }
    }

    synchronized void addContribution(Contribution contribution) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("INSERT INTO contributions (property_id, name, amount, contribution_date) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, contribution.propertyId()); statement.setString(2, contribution.name()); statement.setBigDecimal(3, contribution.amount()); statement.setString(4, contribution.date().toString()); statement.executeUpdate();
        } catch (SQLException exception) { throw new IllegalStateException("Impossible d'enregistrer l'apport.", exception); }
    }

    synchronized boolean propertyExists(long id) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM properties WHERE id = ?)")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de verifier le bien.", exception);
        }
    }

    synchronized void addProperty(Property property) {
        String query = "INSERT INTO properties (name, kind, commune, address, insee, area, purchase, purchase_date, estimate, estimate_high, estimate_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, property.name());
            statement.setString(2, property.kind());
            statement.setString(3, property.commune());
            statement.setString(4, property.address()); statement.setString(5, property.insee());
            statement.setBigDecimal(6, property.area()); statement.setBigDecimal(7, property.purchase());
            statement.setString(8, property.purchaseDate().toString()); statement.setBigDecimal(9, property.estimate()); statement.setBigDecimal(10, property.estimateHigh());
            statement.setString(11, property.estimateDate().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'enregistrer le bien.", exception);
        }
    }

    synchronized void updateProperty(Property property) {
        String query = "UPDATE properties SET name=?, kind=?, commune=?, address=?, area=?, purchase=?, purchase_date=?, estimate=?, estimate_high=?, estimate_date=? WHERE id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, property.name()); statement.setString(2, property.kind()); statement.setString(3, property.commune()); statement.setString(4, property.address());
            statement.setBigDecimal(5, property.area()); statement.setBigDecimal(6, property.purchase()); statement.setString(7, property.purchaseDate().toString());
            statement.setBigDecimal(8, property.estimate()); statement.setBigDecimal(9, property.estimateHigh()); statement.setString(10, property.estimateDate().toString()); statement.setLong(11, property.id());
            statement.executeUpdate();
        } catch (SQLException exception) { throw new IllegalStateException("Impossible de modifier le bien.", exception); }
    }

    synchronized long addLoan(Loan loan) {
        String query = "INSERT INTO loans (property_id, name, initial, reference_balance, reference_date, rate, duration_months, start_date, due_day, payment, insurance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, loan.propertyId());
            statement.setString(2, loan.name());
            statement.setBigDecimal(3, loan.initialAmount());
            statement.setBigDecimal(4, loan.referenceBalance());
            statement.setString(5, loan.referenceDate().toString());
            statement.setBigDecimal(6, loan.annualRate());
            statement.setInt(7, loan.durationMonths());
            statement.setString(8, loan.startDate().toString());
            statement.setInt(9, loan.dueDay());
            statement.setBigDecimal(10, loan.monthlyPayment());
            statement.setBigDecimal(11, loan.monthlyInsurance());
            try (ResultSet keys = statement.executeQuery()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new IllegalStateException("Identifiant du pret introuvable.");
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'enregistrer le pret.", exception);
        }
    }

    synchronized Loan loan(long id) {
        String query = "SELECT loans.*, properties.name AS property_name FROM loans JOIN properties ON properties.id = loans.property_id WHERE loans.id = ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new Loan(rows.getLong("id"), rows.getLong("property_id"), rows.getString("property_name"), rows.getString("name"),
                        rows.getBigDecimal("initial"), rows.getBigDecimal("reference_balance"), LocalDate.parse(rows.getString("reference_date")),
                        rows.getBigDecimal("rate"), rows.getInt("duration_months"), LocalDate.parse(rows.getString("start_date")),
                        rows.getInt("due_day"), rows.getBigDecimal("payment"), rows.getBigDecimal("insurance"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de lire le pret.", exception);
        }
    }

    synchronized List<ScheduleLine> scheduleLines(long loanId) {
        List<ScheduleLine> results = new ArrayList<>();
        String query = "SELECT * FROM loan_schedule_lines WHERE loan_id = ? ORDER BY due_date, id";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, loanId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) results.add(new ScheduleLine(LocalDate.parse(rows.getString("due_date")), rows.getString("type"),
                        rows.getBigDecimal("amount"), rows.getBigDecimal("principal_repaid"), rows.getBigDecimal("interest"),
                        rows.getBigDecimal("fees"), rows.getBigDecimal("remaining_principal"), rows.getBigDecimal("annual_rate")));
            }
            return results;
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible de lire les echeances.", exception);
        }
    }

    synchronized void replaceScheduleLines(long loanId, List<ScheduleLine> lines) {
        String insert = "INSERT INTO loan_schedule_lines (loan_id, due_date, type, amount, principal_repaid, interest, fees, remaining_principal, annual_rate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM loan_schedule_lines WHERE loan_id = ?");
                    PreparedStatement statement = connection.prepareStatement(insert)) {
                delete.setLong(1, loanId);
                delete.executeUpdate();
                for (ScheduleLine line : lines) {
                    statement.setLong(1, loanId); statement.setString(2, line.date().toString()); statement.setString(3, line.type());
                    nullableDecimal(statement, 4, line.amount()); nullableDecimal(statement, 5, line.principalRepaid());
                    nullableDecimal(statement, 6, line.interest()); nullableDecimal(statement, 7, line.fees());
                    nullableDecimal(statement, 8, line.remainingPrincipal()); nullableDecimal(statement, 9, line.annualRate());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'enregistrer les echeances.", exception);
        }
    }

    private static void nullableDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.REAL); else statement.setBigDecimal(index, value);
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
}
