// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.TestData;
import fr.patria.contribution.Contribution;
import fr.patria.loan.LineType;
import fr.patria.loan.ScheduleLine;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertyRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void storesAmountsExactlyAndKeepsMissingEstimatesNull() {
        TestData.Stores stores = TestData.stores(directory);
        long id = stores.properties().insert(TestData.property("Maison", "250000.10", null, "400000"));

        Property stored = stores.properties().find(id).orElseThrow();

        assertEquals(new BigDecimal("250000.10"), stored.purchase());
        assertEquals(null, stored.estimateLow());
        assertEquals(0, new BigDecimal("400000").compareTo(stored.estimateHigh()));
        assertEquals(0, new BigDecimal("95.5").compareTo(stored.area()));
        assertEquals(0, new BigDecimal("0.08").compareTo(stored.saleFeesRate()));
        stores.database().close();
    }

    @Test
    void deletingPropertyRemovesItsLoansSchedulesAndContributions() {
        TestData.Stores stores = TestData.stores(directory);
        long kept = stores.properties().insert(TestData.property("Gardé", "100000", null, null));
        long removed = stores.properties().insert(TestData.property("Supprimé", "100000", null, null));
        long loan = stores.loans().create(removed, "Prêt", List.of(new ScheduleLine(LocalDate.of(2026, 1, 5), LineType.INSTALLMENT,
                new BigDecimal("100"), new BigDecimal("70"), null, null, new BigDecimal("930"), new BigDecimal("3"))));
        stores.contributions().insert(new Contribution(0, removed, "Épargne", new BigDecimal("1000"), LocalDate.of(2024, 1, 1)));

        stores.properties().delete(removed);

        assertTrue(stores.properties().find(removed).isEmpty());
        assertTrue(stores.loans().find(loan).isEmpty());
        assertTrue(stores.loans().schedule(loan).isEmpty());
        assertTrue(stores.contributions().all().isEmpty());
        assertEquals(List.of(kept), stores.properties().all().stream().map(Property::id).toList());
        stores.database().close();
    }
}
