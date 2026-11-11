// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.storage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversions entre Java et la base. Regle : aucun nombre flottant en base.
 * Montants en centimes (INTEGER), taux et surfaces en texte decimal exact (TEXT), dates ISO (TEXT).
 */
public final class Sql {
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private Sql() {
    }

    public static <T> List<T> list(Connection connection, String sql, RowMapper<T> mapper, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); ResultSet rows = statement.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (rows.next()) results.add(mapper.map(rows));
            return results;
        }
    }

    public static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            return statement.executeUpdate();
        }
    }

    /** INSERT ... RETURNING id. */
    public static long insert(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); ResultSet keys = statement.executeQuery()) {
            if (!keys.next()) throw new SQLException("Aucun identifiant renvoye.");
            return keys.getLong(1);
        }
    }

    /**
     * Prepare une requete. Conversion des parametres : {@link Cents} -> INTEGER, {@link BigDecimal} -> TEXT exact,
     * {@link LocalDate} -> TEXT ISO, {@code null} -> NULL.
     */
    public static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            Object value = parameters[i];
            int index = i + 1;
            switch (value) {
                case null -> statement.setNull(index, Types.NULL);
                case Cents cents -> {
                    if (cents.amount() == null) statement.setNull(index, Types.INTEGER);
                    else statement.setLong(index, toCents(cents.amount()));
                }
                case BigDecimal decimal -> statement.setString(index, decimal.stripTrailingZeros().toPlainString());
                case LocalDate date -> statement.setString(index, date.toString());
                case Long number -> statement.setLong(index, number);
                case Integer number -> statement.setInt(index, number);
                case String text -> statement.setString(index, text);
                case byte[] bytes -> statement.setBytes(index, bytes);
                default -> throw new IllegalArgumentException("Type non pris en charge : " + value.getClass());
            }
        }
        return statement;
    }

    /** Marque un montant a stocker en centimes. */
    public record Cents(BigDecimal amount) {
    }

    public static Cents cents(BigDecimal amount) {
        return new Cents(amount);
    }

    static long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** Lit un montant stocke en centimes (null si NULL). */
    public static BigDecimal money(ResultSet row, String column) throws SQLException {
        long cents = row.getLong(column);
        return row.wasNull() ? null : BigDecimal.valueOf(cents, 2);
    }

    /** Lit un decimal stocke en texte (null si NULL ou vide). */
    public static BigDecimal decimal(ResultSet row, String column) throws SQLException {
        String text = row.getString(column);
        return text == null || text.isBlank() ? null : new BigDecimal(text).stripTrailingZeros();
    }

    public static LocalDate date(ResultSet row, String column) throws SQLException {
        String text = row.getString(column);
        return text == null || text.isBlank() ? null : LocalDate.parse(text);
    }
}
