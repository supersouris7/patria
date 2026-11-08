package fr.patria.savings;

import fr.patria.common.ValidationException;
import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Comptes d'epargne et lignes de bourse du foyer (tables {@code savings_*}, migration v16). */
public final class SavingsRepository {
    /**
     * Un placement d'un membre. {@code rate} : taux annuel saisi pour un placement non reglemente (assurance-vie fonds
     * euros, livret bancaire), {@code null} sinon ; {@code openedOn} : date d'ouverture (PEA : fiscalite apres 5 ans).
     */
    public record Account(long id, long personId, SavingsKind kind, String name, BigDecimal balance, LocalDate balanceDate, BigDecimal rate,
                          LocalDate openedOn, Contribution contribution) {
        public Account(long id, long personId, SavingsKind kind, String name, BigDecimal balance, LocalDate balanceDate, BigDecimal rate, LocalDate openedOn) {
            this(id, personId, kind, name, balance, balanceDate, rate, openedOn, null);
        }
    }

    /**
     * Versement programme : {@code amount} a chaque echeance de {@code frequency} depuis {@code start} ; {@code item} :
     * la ligne de budget qui le reprend (reste a vivre), {@code null} si elle a ete supprimee.
     */
    public record Contribution(BigDecimal amount, fr.patria.common.Frequency frequency, LocalDate start, Long item) {
    }

    /**
     * Une ligne de bourse : {@code symbol} au format Yahoo Finance (ex. AI.PA), {@code cost} prix de revient total,
     * {@code price} cours saisi a la main ({@code null} : cours du jour par l'API).
     */
    public record Position(long id, long accountId, String symbol, String label, BigDecimal quantity, BigDecimal cost, BigDecimal price) {
    }

    private final Database database;

    public SavingsRepository(Database database) {
        this.database = database;
    }

    public List<Account> accounts() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM savings_accounts ORDER BY person_id, kind, name", row -> new Account(
                row.getLong("id"), row.getLong("person_id"), SavingsKind.valueOf(row.getString("kind")), row.getString("name"), Sql.money(row, "balance"),
                LocalDate.parse(row.getString("balance_date")), row.getString("rate") == null ? null : new BigDecimal(row.getString("rate")),
                row.getString("opened_on") == null ? null : LocalDate.parse(row.getString("opened_on")), contribution(row))));
    }

    private static Contribution contribution(java.sql.ResultSet row) throws java.sql.SQLException {
        BigDecimal amount = Sql.money(row, "contribution");
        if (amount == null) return null;
        long item = row.getLong("contribution_item");
        return new Contribution(amount, fr.patria.common.Frequency.valueOf(row.getString("contribution_frequency")), LocalDate.parse(row.getString("contribution_start")),
                row.wasNull() ? null : item);
    }

    /** Pose ou retire ({@code null}) le versement programme d'un placement. */
    public void setContribution(long id, Contribution contribution) {
        database.query(connection -> Sql.update(connection,
                "UPDATE savings_accounts SET contribution = ?, contribution_frequency = ?, contribution_start = ?, contribution_item = ? WHERE id = ?",
                Sql.cents(contribution == null ? null : contribution.amount()), contribution == null ? null : contribution.frequency().name(),
                contribution == null ? null : contribution.start().toString(), contribution == null ? null : contribution.item(), id));
    }

    /** Solde d'un mois (fin de mois ou dernier connu), pour le graphique de l'epargne. */
    public void recordHistory(long accountId, java.time.YearMonth month, BigDecimal balance) {
        database.query(connection -> Sql.update(connection,
                "INSERT INTO savings_history (account_id, month, balance) VALUES (?, ?, ?) ON CONFLICT(account_id, month) DO UPDATE SET balance = excluded.balance",
                accountId, month.toString(), Sql.cents(balance)));
    }

    /** Historique : compte → mois → solde. */
    public java.util.Map<Long, java.util.SortedMap<java.time.YearMonth, BigDecimal>> history() {
        java.util.Map<Long, java.util.SortedMap<java.time.YearMonth, BigDecimal>> result = new java.util.HashMap<>();
        database.query(connection -> Sql.list(connection, "SELECT account_id, month, balance FROM savings_history", row -> result
                .computeIfAbsent(row.getLong(1), key -> new java.util.TreeMap<>()).put(java.time.YearMonth.parse(row.getString(2)), Sql.money(row, "balance"))));
        return result;
    }

    public Optional<Account> account(long id) {
        return accounts().stream().filter(account -> account.id() == id).findFirst();
    }

    public List<Position> positions() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM savings_positions ORDER BY account_id, label", row -> new Position(
                row.getLong("id"), row.getLong("account_id"), row.getString("symbol"), row.getString("label"), new BigDecimal(row.getString("quantity")),
                Sql.money(row, "cost"), row.getObject("price") == null ? null : Sql.money(row, "price"))));
    }

    public long addAccount(Account account) {
        return database.query(connection -> Sql.insert(connection, """
                INSERT INTO savings_accounts (person_id, kind, name, balance, balance_date, rate, opened_on) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                account.personId(), account.kind().name(), account.name(), Sql.cents(account.balance()), account.balanceDate().toString(),
                account.rate() == null ? null : account.rate().toPlainString(), account.openedOn() == null ? null : account.openedOn().toString()));
    }

    public void updateBalance(long id, BigDecimal balance, LocalDate date) {
        database.query(connection -> Sql.update(connection, "UPDATE savings_accounts SET balance = ?, balance_date = ? WHERE id = ?", Sql.cents(balance), date.toString(), id));
    }

    public void deleteAccount(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM savings_accounts WHERE id = ?", id));
    }

    public long addPosition(Position position) {
        if (position.quantity().signum() <= 0) throw new ValidationException("Quantité invalide.");
        return database.query(connection -> Sql.insert(connection, "INSERT INTO savings_positions (account_id, symbol, label, quantity, cost, price) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                position.accountId(), position.symbol(), position.label(), position.quantity().toPlainString(), Sql.cents(position.cost()),
                Sql.cents(position.price())));
    }

    public void deletePosition(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM savings_positions WHERE id = ?", id));
    }

    /** Reglage du plan d'epargne : {@code lep} (eligible au LEP : oui, non, vide = inconnu), {@code months} (objectif de precaution). */
    public String preference(String key) {
        return database.query(connection -> Sql.list(connection, "SELECT value FROM savings_preferences WHERE key = ?", row -> row.getString(1), key))
                .stream().findFirst().orElse("");
    }

    public void setPreference(String key, String value) {
        database.query(connection -> Sql.update(connection,
                "INSERT INTO savings_preferences (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value));
    }
}
