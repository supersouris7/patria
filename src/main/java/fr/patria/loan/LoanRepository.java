// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.loan;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stockage des prets (table {@code loans}) et de leurs echeanciers (table {@code schedule_lines}). */
public final class LoanRepository {
    private final Database database;

    public LoanRepository(Database database) {
        this.database = database;
    }

    public List<Loan> all() {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM loans ORDER BY name COLLATE NOCASE, id", LoanRepository::map));
    }

    public List<Loan> forProperty(long propertyId) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM loans WHERE property_id = ? ORDER BY name COLLATE NOCASE, id", LoanRepository::map, propertyId));
    }

    public Optional<Loan> find(long id) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM loans WHERE id = ?", LoanRepository::map, id)).stream().findFirst();
    }

    public List<ScheduleLine> schedule(long loanId) {
        return schedules(List.of(loanId)).getOrDefault(loanId, List.of());
    }

    /** Echeanciers de plusieurs prets, indexes par identifiant de pret, tries par date. */
    public Map<Long, List<ScheduleLine>> schedules(Collection<Long> loanIds) {
        if (loanIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(loanIds.size(), "?"));
        return database.query(connection -> {
            Map<Long, List<ScheduleLine>> result = new HashMap<>();
            Sql.list(connection, "SELECT * FROM schedule_lines WHERE loan_id IN (" + placeholders + ") ORDER BY loan_id, due_date, id",
                    row -> result.computeIfAbsent(row.getLong("loan_id"), key -> new ArrayList<>()).add(mapLine(row)), loanIds.toArray());
            return result;
        });
    }

    /** Cree le pret et son echeancier ensemble. */
    public long create(long propertyId, String name, List<ScheduleLine> lines) {
        return database.transaction(connection -> {
            long id = Sql.insert(connection, "INSERT INTO loans (property_id, name) VALUES (?, ?) RETURNING id", propertyId, name);
            insertLines(connection, id, lines);
            return id;
        });
    }

    public void replaceSchedule(long loanId, List<ScheduleLine> lines) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM schedule_lines WHERE loan_id = ?", loanId);
            insertLines(connection, loanId, lines);
            return null;
        });
    }

    public void rename(long loanId, String name) {
        database.query(connection -> Sql.update(connection, "UPDATE loans SET name = ? WHERE id = ?", name, loanId));
    }

    /** Supprime le pret ; son echeancier part en cascade. */
    public void delete(long loanId) {
        database.query(connection -> Sql.update(connection, "DELETE FROM loans WHERE id = ?", loanId));
    }

    private static void insertLines(Connection connection, long loanId, List<ScheduleLine> lines) throws SQLException {
        for (ScheduleLine line : lines) {
            Sql.update(connection, """
                    INSERT INTO schedule_lines (loan_id, due_date, type, amount_cents, principal_cents, interest_cents, fees_cents, remaining_cents, annual_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    loanId, line.date(), line.type().code(), cents(line.amount()), cents(line.principalRepaid()), cents(line.interest()),
                    cents(line.fees()), cents(line.remainingPrincipal()), line.annualRate());
        }
    }

    private static Loan map(ResultSet row) throws SQLException {
        return new Loan(row.getLong("id"), row.getLong("property_id"), row.getString("name"));
    }

    private static ScheduleLine mapLine(ResultSet row) throws SQLException {
        return new ScheduleLine(Sql.date(row, "due_date"), LineType.fromCode(row.getString("type")).orElse(LineType.ADJUSTMENT),
                Sql.money(row, "amount_cents"), Sql.money(row, "principal_cents"), Sql.money(row, "interest_cents"),
                Sql.money(row, "fees_cents"), Sql.money(row, "remaining_cents"), Sql.decimal(row, "annual_rate"));
    }
}
