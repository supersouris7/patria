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
}
