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

    void save(MarketPrice price) {
        database.query(connection -> Sql.update(connection, """
                INSERT INTO market_prices (property_id, fingerprint, computed_on, status, median_cents, low_cents, high_cents, sales_count,
                    scope, place, period, index_month, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (property_id) DO UPDATE SET fingerprint = excluded.fingerprint, computed_on = excluded.computed_on,
                    status = excluded.status, median_cents = excluded.median_cents, low_cents = excluded.low_cents, high_cents = excluded.high_cents,
                    sales_count = excluded.sales_count, scope = excluded.scope, place = excluded.place, period = excluded.period,
                    index_month = excluded.index_month, message = excluded.message""",
                price.propertyId(), price.fingerprint(), price.computedOn(), price.status().name(), cents(price.median()), cents(price.low()),
                cents(price.high()), price.salesCount(), price.scope() == null ? null : price.scope().name(), price.place(), price.period(),
                price.indexMonth() == null ? null : price.indexMonth().toString(), price.message()));
    }

    private static MarketPrice map(ResultSet row) throws SQLException {
        String scope = row.getString("scope");
        String indexMonth = row.getString("index_month");
        return new MarketPrice(row.getLong("property_id"), row.getString("fingerprint"), Sql.date(row, "computed_on"),
                MarketPrice.Status.valueOf(row.getString("status")), Sql.money(row, "median_cents"), Sql.money(row, "low_cents"),
                Sql.money(row, "high_cents"), row.getInt("sales_count"), scope == null ? null : MarketPrice.Scope.valueOf(scope),
                row.getString("place"), row.getString("period"), indexMonth == null ? null : YearMonth.parse(indexMonth), row.getString("message"));
    }
}
