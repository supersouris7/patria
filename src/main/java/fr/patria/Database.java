package fr.patria;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Database {
    private static final String LOAN_SELECT = "SELECT loans.*, properties.name AS property_name FROM loans JOIN properties ON properties.id = loans.property_id";
    private final String url;

    Database(Path path) {
        this.url = "jdbc:sqlite:" + path.toAbsolutePath();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
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
                        estimate REAL NOT NULL, estimate_high REAL NOT NULL DEFAULT 0, estimate_date TEXT NOT NULL,
                        sale_fees_rate REAL NOT NULL DEFAULT 0.08,
                        recurring_fees_annual REAL NOT NULL DEFAULT 0
                    )
                    """);
            tryExecute(statement, "ALTER TABLE properties ADD COLUMN address TEXT NOT NULL DEFAULT ''");
            if (tryExecute(statement, "ALTER TABLE properties ADD COLUMN estimate_high REAL NOT NULL DEFAULT 0")) {
                statement.execute("UPDATE properties SET estimate_high = estimate WHERE estimate_high = 0");
            }
            tryExecute(statement, "ALTER TABLE properties ADD COLUMN sale_fees_rate REAL NOT NULL DEFAULT 0.08");
            tryExecute(statement, "ALTER TABLE properties ADD COLUMN recurring_fees_annual REAL NOT NULL DEFAULT 0");
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
            tryExecute(statement, "ALTER TABLE contributions ADD COLUMN contribution_date TEXT NOT NULL DEFAULT ''");
            statement.execute("UPDATE contributions SET contribution_date = date('now') WHERE contribution_date = ''");
            statement.execute("CREATE INDEX IF NOT EXISTS loan_schedule_lines_loan ON loan_schedule_lines(loan_id, due_date)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossible d'initialiser SQLite.", exception);
        }
    }

    private static boolean tryExecute(Statement statement, String sql) {
        try {
            statement.execute(sql);
            return true;
        } catch (SQLException ignored) {
            return false; // colonne deja presente
        }
    }

    // ---- Biens ----

    synchronized List<Property> properties() {
        return query("Impossible de lire les biens.", connection -> {
            List<Property> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM properties ORDER BY name COLLATE NOCASE, id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) results.add(property(rows));
            }
            return results;
        });
    }

    synchronized Property property(long id) {
        return query("Impossible de lire le bien.", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM properties WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? property(rows) : null;
                }
            }
        });
    }

    synchronized boolean propertyExists(long id) {
        return property(id) != null;
    }

    synchronized long addProperty(Property property) {
        String sql = "INSERT INTO properties (name, kind, commune, address, insee, area, purchase, purchase_date, estimate, estimate_high, estimate_date, sale_fees_rate, recurring_fees_annual) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        return query("Impossible d'enregistrer le bien.", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, property.name());
                statement.setString(2, property.kind());
                statement.setString(3, property.commune());
                statement.setString(4, property.address());
                statement.setString(5, property.insee() == null ? "" : property.insee());
                bindPropertyValues(statement, property, 6);
                try (ResultSet keys = statement.executeQuery()) {
                    if (keys.next()) return keys.getLong(1);
                }
                throw new SQLException("Identifiant du bien introuvable.");
            }
        });
    }

    synchronized void updateProperty(Property property) {
        String sql = "UPDATE properties SET name=?, kind=?, commune=?, address=?, area=?, purchase=?, purchase_date=?, estimate=?, estimate_high=?, estimate_date=?, sale_fees_rate=?, recurring_fees_annual=? WHERE id=?";
        query("Impossible de modifier le bien.", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, property.name());
                statement.setString(2, property.kind());
                statement.setString(3, property.commune());
                statement.setString(4, property.address());
                bindPropertyValues(statement, property, 5);
                statement.setLong(13, property.id());
                return statement.executeUpdate();
            }
        });
    }

    private static void bindPropertyValues(PreparedStatement statement, Property property, int from) throws SQLException {
        statement.setBigDecimal(from, property.area());
        statement.setBigDecimal(from + 1, property.purchase());
        statement.setString(from + 2, property.purchaseDate().toString());
        statement.setBigDecimal(from + 3, property.estimate());
        statement.setBigDecimal(from + 4, property.estimateHigh());
        statement.setString(from + 5, property.estimateDate().toString());
        statement.setBigDecimal(from + 6, property.saleFeesRate() == null ? Valuation.DEFAULT_SALE_FEES : property.saleFeesRate());
        statement.setBigDecimal(from + 7, property.recurringFeesAnnual() == null ? BigDecimal.ZERO : property.recurringFeesAnnual());
    }

    /** Supprime un bien avec ses prets, echeanciers et apports. */
    synchronized void deleteProperty(long id) {
        transaction("Impossible de supprimer le bien.", connection -> {
            execute(connection, "DELETE FROM loan_schedule_lines WHERE loan_id IN (SELECT id FROM loans WHERE property_id = ?)", id);
            execute(connection, "DELETE FROM loans WHERE property_id = ?", id);
            execute(connection, "DELETE FROM contributions WHERE property_id = ?", id);
            execute(connection, "DELETE FROM properties WHERE id = ?", id);
            return null;
        });
    }

    // ---- Prets ----

    synchronized List<Loan> loans() {
        return loans(LOAN_SELECT + " ORDER BY properties.name COLLATE NOCASE, loans.name COLLATE NOCASE", null);
    }

    synchronized List<Loan> loansForProperty(long propertyId) {
        return loans(LOAN_SELECT + " WHERE loans.property_id = ? ORDER BY loans.name COLLATE NOCASE", propertyId);
    }

    synchronized Loan loan(long id) {
        List<Loan> loans = loans(LOAN_SELECT + " WHERE loans.id = ?", id);
        return loans.isEmpty() ? null : loans.getFirst();
    }

    private List<Loan> loans(String sql, Long parameter) {
        return query("Impossible de lire les prêts.", connection -> {
            List<Loan> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (parameter != null) statement.setLong(1, parameter);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) results.add(loan(rows));
                }
            }
            return results;
        });
    }

    /** Cree le pret et son echeancier dans une meme transaction. */
    synchronized long createLoan(Loan loan, List<ScheduleLine> lines) {
        String sql = "INSERT INTO loans (property_id, name, initial, reference_balance, reference_date, rate, duration_months, start_date, due_day, payment, insurance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        return transaction("Impossible d'enregistrer le prêt.", connection -> {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
                    if (!keys.next()) throw new SQLException("Identifiant du prêt introuvable.");
                    id = keys.getLong(1);
                }
            }
            insertLines(connection, id, lines);
            return id;
        });
    }

    /** Remplace l'echeancier et recalcule les valeurs de reference du pret. */
    synchronized void replaceSchedule(Loan derived, List<ScheduleLine> lines) {
        transaction("Impossible d'enregistrer les échéances.", connection -> {
            execute(connection, "DELETE FROM loan_schedule_lines WHERE loan_id = ?", derived.id());
            insertLines(connection, derived.id(), lines);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE loans SET initial=?, reference_balance=?, reference_date=?, rate=?, duration_months=?, start_date=?, due_day=?, payment=? WHERE id=?")) {
                statement.setBigDecimal(1, derived.initialAmount());
                statement.setBigDecimal(2, derived.referenceBalance());
                statement.setString(3, derived.referenceDate().toString());
                statement.setBigDecimal(4, derived.annualRate());
                statement.setInt(5, derived.durationMonths());
                statement.setString(6, derived.startDate().toString());
                statement.setInt(7, derived.dueDay());
                statement.setBigDecimal(8, derived.monthlyPayment());
                statement.setLong(9, derived.id());
                statement.executeUpdate();
            }
            return null;
        });
    }

    synchronized void renameLoan(long id, String name) {
        transaction("Impossible de renommer le prêt.", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE loans SET name = ? WHERE id = ?")) {
                statement.setString(1, name);
                statement.setLong(2, id);
                return statement.executeUpdate();
            }
        });
    }

    synchronized void deleteLoan(long id) {
        transaction("Impossible de supprimer le prêt.", connection -> {
            execute(connection, "DELETE FROM loan_schedule_lines WHERE loan_id = ?", id);
            execute(connection, "DELETE FROM loans WHERE id = ?", id);
            return null;
        });
    }

    synchronized List<ScheduleLine> scheduleLines(long loanId) {
        return schedules("SELECT * FROM loan_schedule_lines WHERE loan_id = ? ORDER BY due_date, id", loanId).getOrDefault(loanId, List.of());
    }

    /** Tous les echeanciers, indexes par pret, en une seule requete. */
    synchronized Map<Long, List<ScheduleLine>> schedules() {
        return schedules("SELECT * FROM loan_schedule_lines ORDER BY loan_id, due_date, id", null);
    }

    private Map<Long, List<ScheduleLine>> schedules(String sql, Long parameter) {
        return query("Impossible de lire les échéances.", connection -> {
            Map<Long, List<ScheduleLine>> results = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (parameter != null) statement.setLong(1, parameter);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        results.computeIfAbsent(rows.getLong("loan_id"), key -> new ArrayList<>()).add(new ScheduleLine(
                                LocalDate.parse(rows.getString("due_date")), rows.getString("type").toUpperCase(java.util.Locale.ROOT),
                                money(rows, "amount"), money(rows, "principal_repaid"), money(rows, "interest"),
                                money(rows, "fees"), money(rows, "remaining_principal"), decimal(rows, "annual_rate", 4)));
                    }
                }
            }
            return results;
        });
    }

    private static void insertLines(Connection connection, long loanId, List<ScheduleLine> lines) throws SQLException {
        String insert = "INSERT INTO loan_schedule_lines (loan_id, due_date, type, amount, principal_repaid, interest, fees, remaining_principal, annual_rate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            for (ScheduleLine line : lines) {
                statement.setLong(1, loanId);
                statement.setString(2, line.date().toString());
                statement.setString(3, line.type());
                nullableDecimal(statement, 4, line.amount());
                nullableDecimal(statement, 5, line.principalRepaid());
                nullableDecimal(statement, 6, line.interest());
                nullableDecimal(statement, 7, line.fees());
                nullableDecimal(statement, 8, line.remainingPrincipal());
                nullableDecimal(statement, 9, line.annualRate());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    // ---- Apports ----

    synchronized List<Contribution> contributions(long propertyId) {
        return contributions("SELECT * FROM contributions WHERE property_id = ? ORDER BY contribution_date DESC, id DESC", propertyId);
    }

    synchronized List<Contribution> allContributions() {
        return contributions("SELECT * FROM contributions ORDER BY contribution_date DESC, id DESC", null);
    }

    private List<Contribution> contributions(String sql, Long parameter) {
        return query("Impossible de lire les apports.", connection -> {
            List<Contribution> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (parameter != null) statement.setLong(1, parameter);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) results.add(contribution(rows));
                }
            }
            return results;
        });
    }

    synchronized Contribution contribution(long id) {
        List<Contribution> results = contributions("SELECT * FROM contributions WHERE id = ?", id);
        return results.isEmpty() ? null : results.getFirst();
    }

    synchronized void addContribution(Contribution contribution) {
        query("Impossible d'enregistrer l'apport.", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO contributions (property_id, name, amount, contribution_date) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, contribution.propertyId());
                statement.setString(2, contribution.name());
                statement.setBigDecimal(3, contribution.amount());
                statement.setString(4, contribution.date().toString());
                return statement.executeUpdate();
            }
        });
    }

    synchronized void updateContribution(Contribution contribution) {
        query("Impossible de modifier l'apport.", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE contributions SET name = ?, amount = ?, contribution_date = ? WHERE id = ?")) {
                statement.setString(1, contribution.name());
                statement.setBigDecimal(2, contribution.amount());
                statement.setString(3, contribution.date().toString());
                statement.setLong(4, contribution.id());
                return statement.executeUpdate();
            }
        });
    }

    synchronized void deleteContribution(long id) {
        query("Impossible de supprimer l'apport.", connection -> execute(connection, "DELETE FROM contributions WHERE id = ?", id));
    }

    // ---- Lecture des lignes ----

    private static Property property(ResultSet rows) throws SQLException {
        BigDecimal saleFeesRate = decimal(rows, "sale_fees_rate", 6);
        BigDecimal recurringFees = money(rows, "recurring_fees_annual");
        return new Property(rows.getLong("id"), rows.getString("name"), rows.getString("kind"),
                rows.getString("commune"), rows.getString("address"), rows.getString("insee"), decimal(rows, "area", 2),
                money(rows, "purchase"), LocalDate.parse(rows.getString("purchase_date")),
                money(rows, "estimate"), money(rows, "estimate_high"), LocalDate.parse(rows.getString("estimate_date")),
                saleFeesRate == null ? Valuation.DEFAULT_SALE_FEES : saleFeesRate, recurringFees == null ? BigDecimal.ZERO : recurringFees);
    }

    private static Loan loan(ResultSet rows) throws SQLException {
        return new Loan(rows.getLong("id"), rows.getLong("property_id"), rows.getString("property_name"), rows.getString("name"),
                money(rows, "initial"), money(rows, "reference_balance"), LocalDate.parse(rows.getString("reference_date")),
                decimal(rows, "rate", 4), rows.getInt("duration_months"), LocalDate.parse(rows.getString("start_date")),
                rows.getInt("due_day"), money(rows, "payment"), money(rows, "insurance"));
    }

    private static Contribution contribution(ResultSet rows) throws SQLException {
        return new Contribution(rows.getLong("id"), rows.getLong("property_id"), rows.getString("name"),
                money(rows, "amount"), LocalDate.parse(rows.getString("contribution_date")));
    }

    /** Les montants sont stockes en REAL : on les ramene au centime pour eviter les artefacts flottants. */
    private static BigDecimal money(ResultSet rows, String column) throws SQLException {
        return decimal(rows, column, 2);
    }

    private static BigDecimal decimal(ResultSet rows, String column, int scale) throws SQLException {
        BigDecimal value = rows.getBigDecimal(column);
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static void nullableDecimal(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.REAL); else statement.setBigDecimal(index, value);
    }

    private static int execute(Connection connection, String sql, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            return statement.executeUpdate();
        }
    }

    private <T> T query(String error, Work<T> work) {
        try (Connection connection = open()) {
            return work.run(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException(error, exception);
        }
    }

    private <T> T transaction(String error, Work<T> work) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(error, exception);
        }
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
