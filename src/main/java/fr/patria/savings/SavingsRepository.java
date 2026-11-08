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
                          LocalDate openedOn) {
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
                row.getString("opened_on") == null ? null : LocalDate.parse(row.getString("opened_on")))));
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
