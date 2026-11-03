package fr.patria.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Evolutions du schema, numerotees et appliquees une seule fois (version stockee dans {@code PRAGMA user_version}).
 *
 * <p>Pour faire evoluer le schema : ajouter une methode {@code toVersionN}, l'appeler dans {@link #apply} et
 * incrementer {@link #LATEST}. Ne jamais modifier une migration deja publiee. Une copie de sauvegarde du
 * fichier est faite automatiquement avant toute migration d'une base existante.
 */
public final class Migrations {
    public static final int LATEST = 9;
    private static final Logger LOG = Logger.getLogger(Migrations.class.getName());

    private Migrations() {
    }

    public static void apply(Database database, Clock clock) {
        database.query(connection -> {
            int version = userVersion(connection);
            if (version > LATEST) throw new IllegalStateException("Base creee par une version plus recente de Patria (schema " + version + ").");
            if (!tableExists(connection, "properties")) {
                // Base neuve : schema de reference de la version 2, puis les memes migrations qu'une base existante.
                createSchema(connection, "");
                setUserVersion(connection, 2);
                version = 2;
            } else if (version < LATEST) {
                backup(connection, database, version, clock);
            }
            if (version < 1) toVersion1(connection);
            if (version < 2) toVersion2(connection);
            if (version < 3) toVersion3(connection);
            if (version < 4) toVersion4(connection, LocalDateTime.now(clock).getYear());
            if (version < 5) toVersion5(connection);
            if (version < 6) toVersion6(connection);
            if (version < 7) toVersion7(connection);
            if (version < 8) toVersion8(connection);
            if (version < 9) toVersion9(connection);
            return null;
        });
    }

    /**
     * Schema de reference de la version 2 (fige : ne plus le modifier). Les versions suivantes s'appliquent par-dessus.
     * {@code suffix} permet de creer des tables temporaires pendant la migration 2.
     */
    private static void createSchema(Connection connection, String suffix) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE properties%s (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL CHECK (kind IN ('APARTMENT', 'HOUSE')),
                        commune TEXT NOT NULL DEFAULT '',
                        address TEXT NOT NULL DEFAULT '',
                        area TEXT,
                        purchase_cents INTEGER NOT NULL DEFAULT 0,
                        purchase_date TEXT NOT NULL,
                        estimate_low_cents INTEGER,
                        estimate_high_cents INTEGER,
                        estimate_date TEXT NOT NULL,
                        sale_fees_rate TEXT NOT NULL DEFAULT '0.08',
                        recurring_fees_cents INTEGER NOT NULL DEFAULT 0,
                        selected_value_cents INTEGER
                    )""".formatted(suffix));
            statement.execute("""
                    CREATE TABLE loans%s (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
                        name TEXT NOT NULL
                    )""".formatted(suffix));
            statement.execute("""
                    CREATE TABLE schedule_lines%s (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        loan_id INTEGER NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
                        due_date TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount_cents INTEGER,
                        principal_cents INTEGER,
                        interest_cents INTEGER,
                        fees_cents INTEGER,
                        remaining_cents INTEGER,
                        annual_rate TEXT
                    )""".formatted(suffix));
            statement.execute("""
                    CREATE TABLE contributions%s (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
                        name TEXT NOT NULL,
                        amount_cents INTEGER NOT NULL,
                        contribution_date TEXT NOT NULL
                    )""".formatted(suffix));
            if (suffix.isEmpty()) createIndexes(statement);
        }
    }

    /** Prix du marche calcule pour chaque bien (cache de 30 jours, voir module market). */
    private static void createMarketPrices(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS market_prices (
                    property_id INTEGER PRIMARY KEY REFERENCES properties(id) ON DELETE CASCADE,
                    fingerprint TEXT NOT NULL,
                    computed_on TEXT NOT NULL,
                    status TEXT NOT NULL,
                    median_cents INTEGER,
                    low_cents INTEGER,
                    high_cents INTEGER,
                    sales_count INTEGER NOT NULL DEFAULT 0,
                    scope TEXT,
                    place TEXT,
                    period TEXT,
                    index_month TEXT,
                    message TEXT
                )""");
    }

    /**
     * Version 4 : charges (taxe fonciere, copropriete, assurance...) par annee et periodes de location.
     * L'ancien montant annuel unique « frais recurrents » devient une charge « Autre » de l'annee en cours.
     */
    private static void toVersion4(Connection connection, int currentYear) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("""
                        CREATE TABLE expenses (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
                            kind TEXT NOT NULL CHECK (kind IN ('PROPERTY_TAX', 'CONDO_FEES', 'INSURANCE', 'OTHER')),
                            year INTEGER NOT NULL,
                            amount_cents INTEGER NOT NULL,
                            UNIQUE (property_id, kind, year)
                        )""");
                statement.execute("""
                        CREATE TABLE rents (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
                            label TEXT NOT NULL DEFAULT '',
                            start_date TEXT NOT NULL,
                            end_date TEXT,
                            monthly_cents INTEGER NOT NULL
                        )""");
                statement.execute("CREATE INDEX expenses_by_property ON expenses(property_id)");
                statement.execute("CREATE INDEX rents_by_property ON rents(property_id)");
                statement.execute("INSERT INTO expenses (property_id, kind, year, amount_cents) SELECT id, 'OTHER', " + currentYear
                        + ", recurring_fees_cents FROM properties WHERE recurring_fees_cents > 0");
                statement.execute("ALTER TABLE properties DROP COLUMN recurring_fees_cents");
                setUserVersion(connection, 4);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Version 5 : type de periode d'occupation (location ou residence principale avec loyer de reference) et
     * nouvelle charge « taxe d'habitation » (table des charges recreee pour elargir sa contrainte).
     */
    private static void toVersion5(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            connection.setAutoCommit(false);
            try {
                statement.execute("ALTER TABLE rents ADD COLUMN kind TEXT NOT NULL DEFAULT 'RENTAL' CHECK (kind IN ('RENTAL', 'OWNER'))");
                statement.execute("""
                        CREATE TABLE expenses_v5 (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
                            kind TEXT NOT NULL CHECK (kind IN ('PROPERTY_TAX', 'HOUSING_TAX', 'CONDO_FEES', 'INSURANCE', 'OTHER')),
                            year INTEGER NOT NULL,
                            amount_cents INTEGER NOT NULL,
                            UNIQUE (property_id, kind, year)
                        )""");
                statement.execute("INSERT INTO expenses_v5 (id, property_id, kind, year, amount_cents) SELECT id, property_id, kind, year, amount_cents FROM expenses");
                statement.execute("DROP TABLE expenses");
                statement.execute("ALTER TABLE expenses_v5 RENAME TO expenses");
                statement.execute("CREATE INDEX expenses_by_property ON expenses(property_id)");
                setUserVersion(connection, 5);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    /** Version 6 : coefficient d'actualisation de l'estimation (indice DVF du departement), garde avec le prix du marche. */
    private static void toVersion6(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE market_prices ADD COLUMN revaluation TEXT");
        }
        setUserVersion(connection, 6);
    }

    /** Version 7 : budget du foyer (personnes, revenus, depenses recurrentes). */
    private static void toVersion7(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE people (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE incomes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        person_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                        label TEXT NOT NULL,
                        kind TEXT NOT NULL CHECK (kind IN ('SALARY', 'BONUS', 'OTHER')),
                        amount_cents INTEGER NOT NULL,
                        frequency TEXT NOT NULL CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL', 'ONCE')),
                        start_date TEXT NOT NULL,
                        end_date TEXT
                    )""");
            statement.execute("""
                    CREATE TABLE budget_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        label TEXT NOT NULL,
                        category TEXT NOT NULL,
                        amount_cents INTEGER NOT NULL,
                        frequency TEXT NOT NULL CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL', 'ONCE')),
                        start_date TEXT NOT NULL,
                        end_date TEXT
                    )""");
            statement.execute("CREATE INDEX incomes_by_person ON incomes(person_id)");
        }
        setUserVersion(connection, 7);
    }

    /**
     * Repartition des depenses, biens et prets entre les personnes du foyer. Sans ligne pour un element, il est
     * partage a parts egales : l'existant n'a pas besoin d'etre converti.
     */
    private static void toVersion8(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE shares (
                        subject TEXT NOT NULL CHECK (subject IN ('BUDGET_ITEM', 'PROPERTY', 'LOAN')),
                        subject_id INTEGER NOT NULL,
                        person_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                        percent INTEGER NOT NULL CHECK (percent BETWEEN 0 AND 100),
                        PRIMARY KEY (subject, subject_id, person_id)
                    )""");
        }
        setUserVersion(connection, 8);
    }

    /** Version 9 : membres de type compte (compte joint) et virements entre membres du foyer. */
    private static void toVersion9(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE people ADD COLUMN kind TEXT NOT NULL DEFAULT 'PERSON' CHECK (kind IN ('PERSON', 'ACCOUNT'))");
            statement.execute("""
                    CREATE TABLE transfers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        from_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                        to_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                        label TEXT NOT NULL,
                        amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
                        frequency TEXT NOT NULL,
                        start_date TEXT NOT NULL,
                        end_date TEXT,
                        CHECK (from_id <> to_id)
                    )""");
        }
        setUserVersion(connection, 9);
    }

    /** Version 3 : prix du marche (DVF) par bien. */
    private static void toVersion3(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createMarketPrices(statement);
        }
        setUserVersion(connection, 3);
    }

    private static void createIndexes(Statement statement) throws SQLException {
        statement.execute("CREATE INDEX IF NOT EXISTS loans_by_property ON loans(property_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS schedule_lines_by_loan ON schedule_lines(loan_id, due_date)");
        statement.execute("CREATE INDEX IF NOT EXISTS contributions_by_property ON contributions(property_id)");
    }

    /** Version 1 : bases anterieures au versionnement, completees colonne par colonne. */
    private static void toVersion1(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            addColumn(statement, "properties", "address TEXT NOT NULL DEFAULT ''");
            if (addColumn(statement, "properties", "estimate_high REAL NOT NULL DEFAULT 0")) {
                statement.execute("UPDATE properties SET estimate_high = estimate WHERE estimate_high = 0");
            }
            addColumn(statement, "properties", "sale_fees_rate REAL NOT NULL DEFAULT 0.08");
            addColumn(statement, "properties", "recurring_fees_annual REAL NOT NULL DEFAULT 0");
            addColumn(statement, "properties", "selected_value REAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS loans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, property_id INTEGER NOT NULL, name TEXT NOT NULL)""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS loan_schedule_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, loan_id INTEGER NOT NULL, due_date TEXT NOT NULL, type TEXT NOT NULL,
                        amount REAL, principal_repaid REAL, interest REAL, fees REAL, remaining_principal REAL, annual_rate REAL)""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS contributions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, property_id INTEGER NOT NULL, name TEXT NOT NULL,
                        amount REAL NOT NULL, contribution_date TEXT NOT NULL DEFAULT '')""");
            addColumn(statement, "contributions", "contribution_date TEXT NOT NULL DEFAULT ''");
            statement.execute("UPDATE contributions SET contribution_date = date('now') WHERE contribution_date = ''");
        }
        setUserVersion(connection, 1);
    }

    /**
     * Version 2 : montants en centimes, taux et surfaces en decimal exact, estimations absentes a NULL au lieu de 0,
     * suppression des colonnes inutilisees, suppression en cascade. Tables recreees selon la procedure SQLite.
     */
    private static void toVersion2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            connection.setAutoCommit(false);
            try {
                createSchema(connection, "_v2");
                statement.execute("""
                        INSERT INTO properties_v2 (id, name, kind, commune, address, area, purchase_cents, purchase_date,
                            estimate_low_cents, estimate_high_cents, estimate_date, sale_fees_rate, recurring_fees_cents, selected_value_cents)
                        SELECT id, name, CASE kind WHEN 'Maison' THEN 'HOUSE' ELSE 'APARTMENT' END, commune, address,
                            CASE WHEN area > 0 THEN printf('%.2f', area) END,
                            CAST(round(purchase * 100) AS INTEGER), purchase_date,
                            CASE WHEN estimate > 0 THEN CAST(round(estimate * 100) AS INTEGER) END,
                            CASE WHEN estimate_high > 0 THEN CAST(round(estimate_high * 100) AS INTEGER) END,
                            estimate_date, printf('%.6f', sale_fees_rate), CAST(round(recurring_fees_annual * 100) AS INTEGER),
                            CAST(round(selected_value * 100) AS INTEGER)
                        FROM properties""");
                statement.execute("""
                        INSERT INTO loans_v2 (id, property_id, name)
                        SELECT id, property_id, name FROM loans WHERE property_id IN (SELECT id FROM properties)""");
                statement.execute("""
                        INSERT INTO schedule_lines_v2 (id, loan_id, due_date, type, amount_cents, principal_cents, interest_cents,
                            fees_cents, remaining_cents, annual_rate)
                        SELECT id, loan_id, due_date, upper(type), CAST(round(amount * 100) AS INTEGER),
                            CAST(round(principal_repaid * 100) AS INTEGER), CAST(round(interest * 100) AS INTEGER),
                            CAST(round(fees * 100) AS INTEGER), CAST(round(remaining_principal * 100) AS INTEGER),
                            CASE WHEN annual_rate IS NOT NULL THEN printf('%.4f', annual_rate) END
                        FROM loan_schedule_lines WHERE loan_id IN (SELECT id FROM loans_v2)""");
                statement.execute("""
                        INSERT INTO contributions_v2 (id, property_id, name, amount_cents, contribution_date)
                        SELECT id, property_id, name, CAST(round(amount * 100) AS INTEGER), contribution_date
                        FROM contributions WHERE property_id IN (SELECT id FROM properties)""");
                for (String table : new String[] {"loan_schedule_lines", "contributions", "loans", "properties"}) {
                    statement.execute("DROP TABLE " + table);
                }
                for (String table : new String[] {"properties", "loans", "schedule_lines", "contributions"}) {
                    statement.execute("ALTER TABLE " + table + "_v2 RENAME TO " + table);
                }
                createIndexes(statement);
                try (ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (violations.next()) throw new SQLException("Cles etrangeres invalides apres migration.");
                }
                setUserVersion(connection, 2);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    /** Copie integrale de la base a cote du fichier d'origine, avant toute migration. */
    private static void backup(Connection connection, Database database, int version, Clock clock) throws SQLException {
        String stamp = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String target = database.path().toAbsolutePath() + ".avant-migration-v" + version + "-" + stamp;
        try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target);
            statement.execute();
        }
        LOG.info("Sauvegarde avant migration : " + target);
    }

    private static boolean addColumn(Statement statement, String table, String definition) throws SQLException {
        String column = definition.substring(0, definition.indexOf(' '));
        try (ResultSet columns = statement.executeQuery("SELECT 1 FROM pragma_table_info('" + table + "') WHERE name = '" + column + "'")) {
            if (columns.next()) return false;
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
        return true;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void setUserVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }
}
