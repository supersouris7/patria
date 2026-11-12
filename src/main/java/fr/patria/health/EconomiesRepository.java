// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Economies realisees (contrats renegocies) et objectifs d'epargne du foyer (tables {@code economies} et {@code goals}, migration v19). */
public final class EconomiesRepository {
    /** Contrat renegocie : montant mensuel avant et apres. */
    record Economy(long id, String kind, String label, BigDecimal before, BigDecimal after, LocalDate doneOn) {
        BigDecimal monthly() {
            return before.subtract(after).max(BigDecimal.ZERO);
        }
    }

    /** Objectif d'epargne : montant vise, compte d'epargne suivi. */
    record Goal(long id, String name, String emoji, BigDecimal target, Long accountId, LocalDate createdOn) {
    }

    private final Database database;

    public EconomiesRepository(Database database) {
        this.database = database;
    }

    List<Economy> economies() {
        return database.query(connection -> Sql.list(connection, "SELECT id, kind, label, before_monthly, after_monthly, done_on FROM economies ORDER BY done_on DESC, id DESC",
                row -> new Economy(row.getLong(1), row.getString(2), row.getString(3), Sql.money(row, "before_monthly"), Sql.money(row, "after_monthly"),
                        LocalDate.parse(row.getString(6)))));
    }

    void addEconomy(String kind, String label, BigDecimal before, BigDecimal after, LocalDate doneOn) {
        database.query(connection -> Sql.update(connection, "INSERT INTO economies (kind, label, before_monthly, after_monthly, done_on) VALUES (?, ?, ?, ?, ?)",
                kind, label, Sql.cents(before), Sql.cents(after), doneOn));
    }

    void deleteEconomy(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM economies WHERE id = ?", id));
    }

    List<Goal> goals() {
        return database.query(connection -> Sql.list(connection, "SELECT id, name, emoji, target, account_id, created_on FROM goals ORDER BY id",
                row -> new Goal(row.getLong(1), row.getString(2), row.getString(3), Sql.money(row, "target"),
                        row.getObject(5) == null ? null : row.getLong(5), LocalDate.parse(row.getString(6)))));
    }

    void addGoal(String name, String emoji, BigDecimal target, Long accountId, LocalDate today) {
        database.query(connection -> Sql.update(connection, "INSERT INTO goals (name, emoji, target, account_id, created_on) VALUES (?, ?, ?, ?, ?)",
                name, emoji, Sql.cents(target), accountId, today));
    }

    void deleteGoal(long id) {
        database.query(connection -> Sql.update(connection, "DELETE FROM goals WHERE id = ?", id));
    }
}
