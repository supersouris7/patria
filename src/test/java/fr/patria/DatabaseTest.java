package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {
    @TempDir
    Path directory;

    @Test
    void storesProperties() {
        Database database = new Database(directory.resolve("patria.sqlite3"));
        database.initialize();
        database.addProperty(new Property(0, "Logement test", "Maison", "Commune", "12345",
                new BigDecimal("95.5"), new BigDecimal("250000"), LocalDate.of(2020, 1, 1),
                new BigDecimal("300000"), LocalDate.of(2025, 1, 1)));

        assertEquals("Logement test", database.properties().getFirst().name());
    }

    @Test
    void replacesScheduleLinesAtomically() {
        Database database = new Database(directory.resolve("patria.sqlite3"));
        database.initialize();
        database.addProperty(new Property(0, "Logement test", "Maison", "Commune", "", BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2020, 1, 1), BigDecimal.ZERO, LocalDate.of(2020, 1, 1)));
        database.addLoan(new Loan(0, database.properties().getFirst().id(), "", "Pret test", new BigDecimal("10000"), new BigDecimal("9000"),
                LocalDate.of(2025, 1, 1), new BigDecimal("3"), 120, LocalDate.of(2020, 1, 1), 5, new BigDecimal("100"), BigDecimal.ZERO));
        long loanId = database.loans().getFirst().id();
        database.replaceScheduleLines(loanId, java.util.List.of(new ScheduleLine(LocalDate.of(2026, 1, 5), "echeance",
                new BigDecimal("100"), new BigDecimal("70"), new BigDecimal("30"), null, new BigDecimal("8930"), new BigDecimal("3"))));

        assertEquals(0, new BigDecimal("70").compareTo(database.scheduleLines(loanId).getFirst().principalRepaid()));
        database.replaceScheduleLines(loanId, java.util.List.of(new ScheduleLine(LocalDate.of(2026, 2, 5), "echeance",
                new BigDecimal("100"), new BigDecimal("71"), null, null, new BigDecimal("8859"), null)));
        assertEquals(1, database.scheduleLines(loanId).size());
        assertEquals(0, new BigDecimal("71").compareTo(database.scheduleLines(loanId).getFirst().principalRepaid()));
    }
}
