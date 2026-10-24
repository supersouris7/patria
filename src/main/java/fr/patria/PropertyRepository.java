package fr.patria;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyRepository {
    private static final RowMapper<Property> MAPPER = PropertyRepository::map;
    private final JdbcTemplate jdbc;

    public PropertyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Property> findAll() {
        return jdbc.query("SELECT * FROM properties ORDER BY id DESC", MAPPER);
    }

    public boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM properties WHERE id = ?)", Boolean.class, id));
    }

    public void insert(Property property) {
        jdbc.update("""
                INSERT INTO properties (name, kind, commune, insee, area, purchase, purchase_date, estimate, estimate_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, property.name(), property.kind(), property.commune(), property.insee(), property.area(),
                property.purchase(), property.purchaseDate().toString(), property.estimate(), property.estimateDate().toString());
    }

    private static Property map(ResultSet result, int rowNum) throws SQLException {
        return new Property(
                result.getLong("id"),
                result.getString("name"),
                result.getString("kind"),
                result.getString("commune"),
                result.getString("insee"),
                result.getBigDecimal("area"),
                result.getBigDecimal("purchase"),
                LocalDate.parse(result.getString("purchase_date")),
                result.getBigDecimal("estimate"),
                LocalDate.parse(result.getString("estimate_date")));
    }
}
