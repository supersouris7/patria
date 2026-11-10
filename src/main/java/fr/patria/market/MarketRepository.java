// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import static fr.patria.storage.Sql.cents;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Dernier prix de marche calcule pour chaque bien (table {@code market_prices}). */
final class MarketRepository {
    private final Database database;

    MarketRepository(Database database) {
        this.database = database;
    }

    Optional<MarketPrice> find(long propertyId) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM market_prices WHERE property_id = ?", MarketRepository::map, propertyId)).stream().findFirst();
    }

    Map<Long, MarketPrice> findAll(Collection<Long> propertyIds) {
        return database.query(connection -> Sql.list(connection, "SELECT * FROM market_prices", MarketRepository::map)).stream()
                .filter(price -> propertyIds.contains(price.propertyId())).collect(Collectors.toMap(MarketPrice::propertyId, Function.identity()));
    }

    /** Enregistre le prix, la position du bien et les ventes retenues (remplacees), en une transaction. */
    void save(MarketService.Computed computed) {
        MarketPrice price = computed.price();
        database.transaction(connection -> {
            save(connection, price);
            MarketAnalysis.Location location = computed.location();
            Sql.update(connection, "UPDATE market_prices SET latitude = ?, longitude = ? WHERE property_id = ?",
                    location == null ? null : String.valueOf(location.latitude()), location == null ? null : String.valueOf(location.longitude()), price.propertyId());
            Sql.update(connection, "DELETE FROM market_sales WHERE property_id = ?", price.propertyId());
            for (MarketSale sale : computed.sales()) {
                Sql.update(connection, """
                        INSERT INTO market_sales (property_id, sold_on, address, area, price_cents, per_sqm_cents, distance_m, latitude, longitude)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", price.propertyId(), sale.date(), sale.address(), sale.area(), cents(sale.price()),
                        cents(sale.perSquareMeter()), sale.distance(), String.valueOf(sale.latitude()), String.valueOf(sale.longitude()));
            }
            return null;
        });
    }

    /** Ventes retenues pour ce prix, de la plus recente a la plus ancienne. */
    MarketSales sales(MarketPrice price) {
        return database.query(connection -> {
            List<String[]> location = Sql.list(connection, "SELECT latitude, longitude FROM market_prices WHERE property_id = ?",
                    row -> new String[]{row.getString("latitude"), row.getString("longitude")}, price.propertyId());
            String latitude = location.isEmpty() ? null : location.getFirst()[0];
            String longitude = location.isEmpty() ? null : location.getFirst()[1];
            List<MarketSale> sales = Sql.list(connection, "SELECT * FROM market_sales WHERE property_id = ? ORDER BY sold_on DESC, id",
                    row -> new MarketSale(Sql.date(row, "sold_on"), row.getString("address"), Sql.decimal(row, "area"), Sql.money(row, "price_cents"),
                            Sql.money(row, "per_sqm_cents"), row.getObject("distance_m") == null ? null : row.getInt("distance_m"), Double.parseDouble(row.getString("latitude")),
                            Double.parseDouble(row.getString("longitude"))), price.propertyId());
            return new MarketSales(price, latitude == null ? null : Double.valueOf(latitude), longitude == null ? null : Double.valueOf(longitude), sales);
        });
    }

    private static void save(java.sql.Connection connection, MarketPrice price) throws SQLException {
        Sql.update(connection, """
                INSERT INTO market_prices (property_id, fingerprint, computed_on, status, median_cents, low_cents, high_cents, sales_count,
                    scope, place, period, index_month, message, revaluation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (property_id) DO UPDATE SET fingerprint = excluded.fingerprint, computed_on = excluded.computed_on,
                    status = excluded.status, median_cents = excluded.median_cents, low_cents = excluded.low_cents, high_cents = excluded.high_cents,
                    sales_count = excluded.sales_count, scope = excluded.scope, place = excluded.place, period = excluded.period,
                    index_month = excluded.index_month, message = excluded.message, revaluation = excluded.revaluation""",
                price.propertyId(), price.fingerprint(), price.computedOn(), price.status().name(), cents(price.median()), cents(price.low()),
                cents(price.high()), price.salesCount(), price.scope() == null ? null : price.scope().name(), price.place(), price.period(),
                price.indexMonth() == null ? null : price.indexMonth().toString(), price.message(), price.revaluation());
    }

    private static MarketPrice map(ResultSet row) throws SQLException {
        String scope = row.getString("scope");
        String indexMonth = row.getString("index_month");
        return new MarketPrice(row.getLong("property_id"), row.getString("fingerprint"), Sql.date(row, "computed_on"),
                MarketPrice.Status.valueOf(row.getString("status")), Sql.money(row, "median_cents"), Sql.money(row, "low_cents"),
                Sql.money(row, "high_cents"), row.getInt("sales_count"), scope == null ? null : MarketPrice.Scope.valueOf(scope),
                row.getString("place"), row.getString("period"), indexMonth == null ? null : YearMonth.parse(indexMonth), row.getString("message"),
                Sql.decimal(row, "revaluation"));
    }
}
