package fr.patria;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LoanRepository {
    private static final RowMapper<Loan> MAPPER = LoanRepository::map;
    private final JdbcTemplate jdbc;

    public LoanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Loan> findAll() {
        return jdbc.query("""
                SELECT loans.*, properties.name AS property_name
                FROM loans JOIN properties ON properties.id = loans.property_id
                ORDER BY loans.id DESC
                """, MAPPER);
    }

    public void insert(Loan loan) {
        jdbc.update("""
                INSERT INTO loans (property_id, name, initial, reference_balance, reference_date,
                                   rate, duration_months, start_date, due_day, payment, insurance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, loan.propertyId(), loan.name(), loan.initialAmount(), loan.referenceBalance(),
                loan.referenceDate().toString(), loan.annualRate(), loan.durationMonths(), loan.startDate().toString(),
                loan.dueDay(), loan.monthlyPayment(), loan.monthlyInsurance());
    }

    private static Loan map(ResultSet result, int rowNum) throws SQLException {
        return new Loan(
                result.getLong("id"),
                result.getLong("property_id"),
                result.getString("property_name"),
                result.getString("name"),
                result.getBigDecimal("initial"),
                result.getBigDecimal("reference_balance"),
                LocalDate.parse(result.getString("reference_date")),
                result.getBigDecimal("rate"),
                result.getInt("duration_months"),
                LocalDate.parse(result.getString("start_date")),
                result.getInt("due_day"),
                result.getBigDecimal("payment"),
                result.getBigDecimal("insurance"));
    }
}
