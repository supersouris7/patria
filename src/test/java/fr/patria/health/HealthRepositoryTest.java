// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static org.junit.jupiter.api.Assertions.*;

import fr.patria.storage.Database;
import fr.patria.storage.Migrations;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HealthRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void choicesArePersonalPersistentAndDoNotHideWorseWarnings() {
        Path file = directory.resolve("foyer.sqlite3");
        Check warning = Check.of("balance", Check.Domain.FINANCES, Check.Level.WARN, "Reste faible", "100 €");
        Check changedAmount = Check.of("balance", Check.Domain.FINANCES, Check.Level.WARN, "Reste faible", "90 €");
        Check worse = Check.of("balance", Check.Domain.FINANCES, Check.Level.BAD, "Reste négatif", "-100 €");
        try (Database database = Database.open(file)) {
            Migrations.apply(database, Clock.systemUTC());
            HealthRepository preferences = new HealthRepository(database);
            preferences.ignore(1, warning.token());
            preferences.ignore(1, warning.token());
            assertEquals(1, preferences.ignored(1).size());
            assertTrue(preferences.ignored(2).isEmpty());
            assertTrue(preferences.ignored(1).contains(changedAmount.token()));
            assertFalse(preferences.ignored(1).contains(worse.token()));
        }
        try (Database database = Database.open(file)) {
            HealthRepository preferences = new HealthRepository(database);
            assertTrue(preferences.ignored(1).contains(warning.token()));
            preferences.restore(1, warning.token());
            assertTrue(preferences.ignored(1).isEmpty());
            preferences.ignore(1, warning.token());
            preferences.forgetUser(1);
            assertTrue(preferences.ignored(1).isEmpty());
        }
    }
}
