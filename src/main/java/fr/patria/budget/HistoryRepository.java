// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Historique du foyer (migration v20) : un releve par mois (totaux, sans detail), l'indice des prix INSEE garde sur
 * place, et les depenses reelles des releves bancaires, en totaux par mois et categorie.
 */
public final class HistoryRepository {
    /** Releve d'un mois ; {@code score} vide pour un mois reconstitue ; {@code rebuilt} : calcule apres coup. */
    public record Snapshot(YearMonth month, BigDecimal income, BigDecimal spending, BigDecimal savings, BigDecimal stocks, BigDecimal estate, BigDecimal debts,
                           Integer score, boolean rebuilt) {
        public BigDecimal net() {
            return savings.add(stocks).add(estate).subtract(debts);
        }

        public BigDecimal balance() {
            return income.subtract(spending);
        }
    }

    private final Database database;

    public HistoryRepository(Database database) {
        this.database = database;
    }

    public SortedMap<YearMonth, Snapshot> snapshots() {
        SortedMap<YearMonth, Snapshot> result = new TreeMap<>();
        database.query(connection -> Sql.list(connection, "SELECT * FROM monthly_snapshots", row -> {
            YearMonth month = YearMonth.parse(row.getString("month"));
            result.put(month, new Snapshot(month, Sql.money(row, "income"), Sql.money(row, "spending"), Sql.money(row, "savings"), Sql.money(row, "stocks"),
                    Sql.money(row, "estate"), Sql.money(row, "debts"), row.getObject("score") == null ? null : row.getInt("score"), row.getInt("rebuilt") == 1));
            return null;
        }));
        return result;
    }

    /** Enregistre (ou remplace) le releve d'un mois ; un releve reel remplace un releve reconstitue, jamais l'inverse. */
    public void save(Snapshot snapshot) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO monthly_snapshots (month, income, spending, savings, stocks, estate, debts, score, rebuilt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(month) DO UPDATE SET income = excluded.income, spending = excluded.spending, savings = excluded.savings, stocks = excluded.stocks,
                estate = excluded.estate, debts = excluded.debts, score = excluded.score, rebuilt = excluded.rebuilt
                WHERE excluded.rebuilt = 0 OR monthly_snapshots.rebuilt = 1""",
                snapshot.month().toString(), Sql.cents(snapshot.income()), Sql.cents(snapshot.spending()), Sql.cents(snapshot.savings()), Sql.cents(snapshot.stocks()),
                Sql.cents(snapshot.estate()), Sql.cents(snapshot.debts()), snapshot.score(), snapshot.rebuilt() ? 1 : 0));
    }

    /** Indice des prix a la consommation (INSEE), mois par mois. */
    public SortedMap<YearMonth, BigDecimal> priceIndex() {
        SortedMap<YearMonth, BigDecimal> result = new TreeMap<>();
        database.query(connection -> Sql.list(connection, "SELECT month, value FROM price_index", row -> {
            result.put(YearMonth.parse(row.getString(1)), new BigDecimal(row.getString(2)));
            return null;
        }));
        return result;
    }

    public void savePriceIndex(Map<YearMonth, BigDecimal> values) {
        if (values.isEmpty()) return;
        database.transaction(connection -> {
            for (Map.Entry<YearMonth, BigDecimal> entry : values.entrySet()) {
                Sql.update(connection, "INSERT INTO price_index (month, value) VALUES (?, ?) ON CONFLICT(month) DO UPDATE SET value = excluded.value",
                        entry.getKey().toString(), entry.getValue().toPlainString());
            }
            return null;
        });
    }

    /** Une operation reelle d'un releve : jour, categorie, montant (negatif : depense). */
    public record Operation(java.time.LocalDate date, String category, BigDecimal amount, String hash) {
    }

    /**
     * Ajoute les operations d'un releve aux totaux par mois et categorie ; une operation deja comptee (meme empreinte)
     * ne l'est pas deux fois. Rend le nombre d'operations nouvelles.
     */
    public int addOperations(List<Operation> operations) {
        return database.transaction(connection -> {
            int added = 0;
            for (Operation operation : operations) {
                if (Sql.update(connection, "INSERT OR IGNORE INTO actual_seen (hash) VALUES (?)", operation.hash()) == 0) continue;
                String category = operation.amount().signum() >= 0 ? "Revenus" : operation.category();
                Sql.update(connection, """
                        INSERT INTO actual_spending (month, category, amount) VALUES (?, ?, ?)
                        ON CONFLICT(month, category) DO UPDATE SET amount = amount + excluded.amount""",
                        YearMonth.from(operation.date()).toString(), category, Sql.cents(operation.amount().abs()));
                added++;
            }
            return added;
        });
    }

    /** Depenses reelles par mois (toutes categories sauf « Revenus »). */
    public SortedMap<YearMonth, BigDecimal> actualSpending() {
        SortedMap<YearMonth, BigDecimal> result = new TreeMap<>();
        database.query(connection -> Sql.list(connection, "SELECT month, SUM(amount) FROM actual_spending WHERE category <> 'Revenus' GROUP BY month", row -> {
            result.put(YearMonth.parse(row.getString(1)), BigDecimal.valueOf(row.getLong(2), 2));
            return null;
        }));
        return result;
    }
}
