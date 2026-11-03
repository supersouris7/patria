package fr.patria.household;

import static fr.patria.storage.Sql.cents;

import fr.patria.common.Frequency;
import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Personnes du foyer (table {@code people}) et leurs revenus (table {@code incomes}). */
public final class HouseholdRepository {
    private final Database database;

    public HouseholdRepository(Database database) {
        this.database = database;
    }

    public List<Person> people() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM people ORDER BY kind DESC, id",
                row -> new Person(row.getLong("id"), row.getString("name"), Person.Kind.valueOf(row.getString("kind")))));
    }

    public long addPerson(String name) {
        return addMember(name, Person.Kind.PERSON);
    }

    public long addMember(String name, Person.Kind kind) {
        return database.query(connection -> Sql.insert(connection, "INSERT INTO people (name, kind) VALUES (?, ?) RETURNING id", name, kind.name()));
    }

    // ---- Virements ----

    public List<Transfer> transfers() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM transfers ORDER BY start_date DESC, id", HouseholdRepository::transfer));
    }

    public Optional<Transfer> transfer(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM transfers WHERE id = ?", HouseholdRepository::transfer, id)).stream().findFirst();
    }

    public long addTransfer(Transfer transfer) {
        return database.query(connection -> Sql.insert(connection,
                "INSERT INTO transfers (from_id, to_id, label, amount_cents, frequency, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                transfer.fromId(), transfer.toId(), transfer.label(), cents(transfer.amount()), transfer.frequency().name(), transfer.start(), transfer.end()));
    }

    public void updateTransfer(Transfer transfer) {
        database.query(connection -> Sql.update(connection,
                "UPDATE transfers SET from_id = ?, to_id = ?, label = ?, amount_cents = ?, frequency = ?, start_date = ?, end_date = ? WHERE id = ?",
                transfer.fromId(), transfer.toId(), transfer.label(), cents(transfer.amount()), transfer.frequency().name(), transfer.start(), transfer.end(), transfer.id()));
    }

    public void deleteTransfer(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM transfers WHERE id = ?", id));
    }

    private static Transfer transfer(ResultSet row) throws SQLException {
        return new Transfer(row.getLong("id"), row.getLong("from_id"), row.getLong("to_id"), row.getString("label"), Sql.money(row, "amount_cents"),
                Frequency.valueOf(row.getString("frequency")), Sql.date(row, "start_date"), Sql.date(row, "end_date"));
    }

    /** Supprime la personne et ses revenus. */
    public void deletePerson(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM people WHERE id = ?", id));
    }

    public List<Income> incomes() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM incomes ORDER BY person_id, start_date DESC, id", HouseholdRepository::income));
    }

    public Optional<Income> income(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM incomes WHERE id = ?", HouseholdRepository::income, id)).stream().findFirst();
    }

    public long addIncome(Income income) {
        return database.query(connection -> Sql.insert(connection,
                "INSERT INTO incomes (person_id, label, kind, amount_cents, frequency, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                income.personId(), income.label(), income.kind().name(), cents(income.amount()), income.frequency().name(), income.start(), income.end()));
    }

    public void updateIncome(Income income) {
        database.query(connection -> Sql.update(connection,
                "UPDATE incomes SET person_id = ?, label = ?, kind = ?, amount_cents = ?, frequency = ?, start_date = ?, end_date = ? WHERE id = ?",
                income.personId(), income.label(), income.kind().name(), cents(income.amount()), income.frequency().name(), income.start(), income.end(), income.id()));
    }

    public void deleteIncome(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM incomes WHERE id = ?", id));
    }

    private static Income income(ResultSet row) throws SQLException {
        return new Income(row.getLong("id"), row.getLong("person_id"), row.getString("label"), IncomeKind.valueOf(row.getString("kind")),
                Sql.money(row, "amount_cents"), Frequency.valueOf(row.getString("frequency")), Sql.date(row, "start_date"), Sql.date(row, "end_date"));
    }
}
