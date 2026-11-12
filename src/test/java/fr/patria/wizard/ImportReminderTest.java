// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import static org.junit.jupiter.api.Assertions.*;

import fr.patria.TestData;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rappel mensuel d'import du releve : 30 jours apres le dernier import, « × » le repousse de 30 jours, banque retenue. */
class ImportReminderTest {
    @TempDir
    Path directory;

    @Test
    void remindsThirtyDaysAfterTheLastImportWithTheRememberedBank() {
        WizardRepository progress = new WizardRepository(TestData.stores(directory).database());
        LocalDate day = LocalDate.of(2026, 9, 1);
        String owner = "user:7";
        assertNull(progress.importDue(owner, day), "aucun import : pas de rappel");
        progress.markImported(owner, day);
        assertNull(progress.importDue(owner, day.plusDays(29)));
        assertEquals(30L, progress.importDue(owner, day.plusDays(30)));
        assertEquals("{\"days\":30,\"bank\":null}", progress.importReminderJson(7, day.plusDays(30)));
        progress.setBank(owner, "LCL", day);
        progress.setBank(owner, "Crédit Agricole", day);
        assertEquals("Crédit Agricole", progress.bank(owner), "une seule banque retenue, la dernière");
        progress.hideBanner(owner, day.plusDays(31));
        assertNull(progress.importDue(owner, day.plusDays(40)), "masqué : pas avant 30 jours");
        assertEquals(61L, progress.importDue(owner, day.plusDays(61)));
        assertNull(progress.firstActivity(owner), "les lignes techniques ne comptent pas comme une étape du guide");
    }
}
