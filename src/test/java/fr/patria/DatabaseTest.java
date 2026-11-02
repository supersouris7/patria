package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
    @TempDir
    Path directory;

    private Database database() {
        Database database = new Database(directory.resolve("patria.sqlite3"));
        database.initialize();
        return database;
    }

    private static Property property(String name) {
        return new Property(0, name, "Maison", "Commune", "", "", new BigDecimal("95.5"), new BigDecimal("250000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("300000"), new BigDecimal("300000"), LocalDate.of(2025, 1, 1), new BigDecimal("0.08"), BigDecimal.ZERO);
    }

    private static ScheduleLine line(LocalDate date, String principal, String remaining) {
        return new ScheduleLine(date, "ECHEANCE", new BigDecimal("100"), new BigDecimal(principal), null, null, new BigDecimal(remaining), new BigDecimal("3"));
    }

    @Test
    void storesPropertiesAndReturnsTheirId() {
        Database database = database();
        long id = database.addProperty(property("Logement test"));

        assertEquals("Logement test", database.property(id).name());
        assertEquals(0, new BigDecimal("95.5").compareTo(database.property(id).area()));
    }

    @Test
    void replacesScheduleAndRecomputesLoan() {
        Database database = database();
        long propertyId = database.addProperty(property("Logement"));
        List<ScheduleLine> first = List.of(line(LocalDate.of(2026, 1, 5), "70", "8930"));
        long loanId = database.createLoan(LoanMath.derive(0, propertyId, "Prêt test", first), first);
        assertEquals(1, database.scheduleLines(loanId).size());

        List<ScheduleLine> second = List.of(line(LocalDate.of(2026, 2, 5), "71", "8859"), line(LocalDate.of(2026, 3, 5), "72", "8787"));
        database.replaceSchedule(LoanMath.derive(loanId, propertyId, "Prêt test", second), second);

        assertEquals(2, database.scheduleLines(loanId).size());
        assertEquals(0, new BigDecimal("8930").compareTo(database.loan(loanId).initialAmount()));
        assertEquals(2, database.loan(loanId).durationMonths());
    }

    @Test
    void deletingPropertyRemovesLoansSchedulesAndContributions() {
        Database database = database();
        long kept = database.addProperty(property("Gardé"));
        long removed = database.addProperty(property("Supprimé"));
        List<ScheduleLine> lines = List.of(line(LocalDate.of(2026, 1, 5), "70", "8930"));
        long loanId = database.createLoan(LoanMath.derive(0, removed, "Prêt", lines), lines);
        database.addContribution(new Contribution(0, removed, "Épargne", new BigDecimal("1000"), LocalDate.of(2024, 1, 1)));

        database.deleteProperty(removed);

        assertNull(database.property(removed));
        assertNull(database.loan(loanId));
        assertTrue(database.schedules().isEmpty());
        assertTrue(database.allContributions().isEmpty());
        assertEquals(1, database.properties().size());
        assertEquals(kept, database.properties().getFirst().id());
    }

    @Test
    void storesSelectedValueOnTheProperty() {
        Database database = database();
        long id = database.addProperty(property("Curseur"));
        assertNull(database.property(id).selectedValue());

        database.setSelectedValue(id, new BigDecimal("320000"));

        assertEquals(0, new BigDecimal("320000").compareTo(database.property(id).selectedValue()));
    }

    @Test
    void roundsFloatingPointAmountsToCents() {
        Database database = database();
        long id = database.addProperty(new Property(0, "Arrondi", "Maison", "", "", "", BigDecimal.ZERO, new BigDecimal("100000.10"), LocalDate.of(2020, 1, 1),
                BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(2020, 1, 1), new BigDecimal("0.08"), BigDecimal.ZERO));

        assertEquals(new BigDecimal("100000.10"), database.property(id).purchase());
    }
}
