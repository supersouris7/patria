package fr.patria;

import fr.patria.contribution.ContributionRepository;
import fr.patria.expense.ExpenseRepository;
import fr.patria.rent.RentRepository;
import fr.patria.loan.LoanRepository;
import fr.patria.property.Property;
import fr.patria.property.PropertyKind;
import fr.patria.property.PropertyRepository;
import fr.patria.storage.Database;
import fr.patria.storage.Migrations;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Donnees et dependances communes aux tests. */
public final class TestData {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("Europe/Paris"));

    public record Stores(Database database, PropertyRepository properties, LoanRepository loans, ContributionRepository contributions,
                         ExpenseRepository expenses, RentRepository rents) {
    }

    private TestData() {
    }

    public static Stores stores(Path directory) {
        Database database = Database.open(directory.resolve("patria.sqlite3"));
        Migrations.apply(database, CLOCK);
        return new Stores(database, new PropertyRepository(database), new LoanRepository(database), new ContributionRepository(database),
                new ExpenseRepository(database), new RentRepository(database));
    }

    public static Property property(String name, String purchase, String low, String high) {
        return new Property(0, name, PropertyKind.HOUSE, "Lyon", "", new BigDecimal("95.5"), new BigDecimal(purchase), LocalDate.of(2020, 1, 1),
                low == null ? null : new BigDecimal(low), high == null ? null : new BigDecimal(high), LocalDate.of(2025, 1, 1),
                new BigDecimal("0.08"), null);
    }
}
