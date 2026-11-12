// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static org.junit.jupiter.api.Assertions.*;

import fr.patria.budget.HistoryRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ProgressionTest {
    private static final YearMonth NOW = YearMonth.of(2026, 11);

    private static HistoryRepository.Snapshot month(YearMonth month, String income, String spending, String savings) {
        return new HistoryRepository.Snapshot(month, new BigDecimal(income), new BigDecimal(spending), new BigDecimal(savings), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 70, false);
    }

    private static Progression.Facts facts(SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots, SortedMap<YearMonth, BigDecimal> imported, double cover) {
        return new Progression.Facts(true, 2, 1, 1, 0, true, false, 4, 1, BigDecimal.valueOf(cover), true, 70, snapshots, imported, NOW);
    }

    @Test
    void levelsAdvanceWithPointsAndShowTheWayToTheNext() {
        assertEquals("Premiers pas", Progression.level(0).name());
        Progression.Level organised = Progression.level(300);
        assertEquals(3, organised.number());
        assertEquals("Organisé", organised.name());
        assertEquals(25, organised.percent(), "300 entre 250 et 450 : un quart du chemin");
        Progression.Level top = Progression.level(5000);
        assertNull(top.next(), "niveau maximal");
        assertEquals(100, top.percent());
    }

    @Test
    void missionsAndStreaksFollowTheData() {
        SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots = new TreeMap<>();
        snapshots.put(NOW.minusMonths(2), month(NOW.minusMonths(2), "2500", "2000", "1000"));
        snapshots.put(NOW.minusMonths(1), month(NOW.minusMonths(1), "2500", "2100", "1200"));
        snapshots.put(NOW, month(NOW, "2500", "2700", "1300"));
        SortedMap<YearMonth, BigDecimal> imported = new TreeMap<>();
        imported.put(NOW.minusMonths(1), BigDecimal.TEN);
        imported.put(NOW.minusMonths(2), BigDecimal.TEN);
        Progression.Facts facts = facts(snapshots, imported, 3.4);
        List<Progression.Mission> missions = Progression.missions(facts);
        Progression.Mission shield = missions.stream().filter(mission -> mission.id().equals("bouclier")).findFirst().orElseThrow();
        assertEquals(66, shield.percent(), "1 et 3 mois faits, pas 6");
        assertTrue(missions.stream().filter(mission -> mission.id().equals("ordre")).findFirst().orElseThrow().done());
        List<Progression.Streak> streaks = Progression.streaks(facts);
        assertEquals(2, streaks.get(0).current(), "relevés : le mois en cours pas encore importé ne casse pas la série");
        assertEquals(2, streaks.get(1).current(), "le mois en cours, pas fini, ne casse pas la série des mois sans déficit");
        assertEquals(2, streaks.get(2).current(), "épargne en hausse deux mois de suite (le premier mois n'a pas de point de comparaison)");
        int xp = Progression.xp(facts, missions);
        assertTrue(xp > Progression.xp(facts(snapshots, new TreeMap<>(), 3.4), Progression.missions(facts(snapshots, new TreeMap<>(), 3.4))),
                "importer des relevés rapporte des points");
    }
}
