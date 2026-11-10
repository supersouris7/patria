// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.savings.MarketFeeds;
import fr.patria.savings.Savings;
import fr.patria.savings.SavingsController;
import fr.patria.savings.SavingsKind;
import fr.patria.savings.SavingsRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HealthSavingsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    private static List<Check> audit(List<SavingsRepository.Account> accounts, List<SavingsRepository.Position> positions, String lep) {
        Savings.Overview overview = Savings.overview(accounts, positions, Map.of(1L, "Paul"), symbol -> Optional.empty(), TODAY);
        Savings.Plan plan = Savings.plan(new BigDecimal("2000"), new BigDecimal("500"), overview, List.of(1L), 6, lep, TODAY);
        return HealthSavings.audit(new SavingsController.Snapshot(overview, plan, Optional.of(new MarketFeeds.Inflation(new BigDecimal("3.4"), YearMonth.of(2026, 10))),
                Optional.empty(), lep, List.of()), TODAY);
    }

    private static SavingsRepository.Account account(long id, SavingsKind kind, String balance, LocalDate opened) {
        return new SavingsRepository.Account(id, 1, kind, kind.label(), new BigDecimal(balance), TODAY, null, opened);
    }

    private static Check find(List<Check> checks, String prefix) {
        return checks.stream().filter(check -> check.id().startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError(prefix + " absent : " + checks));
    }

    @Test
    void auditsCushionSleepingCashLepAndInflation() {
        List<Check> checks = audit(List.of(account(1, SavingsKind.CURRENT, "9000", null), account(2, SavingsKind.LIVRET_A, "22950", null)), List.of(), "oui");
        assertEquals(Check.Level.GOOD, find(checks, "savings-cushion").level(), "31 950 € pour 2 000 € par mois");
        assertEquals(Check.Level.WARN, find(checks, "savings-sleeping").level());
        assertTrue(find(checks, "savings-ceiling-2").title().contains("plafond atteint"));
        assertTrue(find(checks, "savings-lep").title().contains("2,5"), "éligible sans LEP");
        assertTrue(find(checks, "savings-inflation").detail().contains("pouvoir d'achat perdu"));

        List<Check> low = audit(List.of(account(1, SavingsKind.LIVRET_A, "1000", null)), List.of(), "");
        assertEquals(Check.Level.BAD, find(low, "savings-cushion").level());
        assertTrue(find(low, "savings-lep-check").detail().contains("23"), "éligibilité à vérifier");
    }

    @Test
    void auditsStocksConcentrationAndYoungPea() {
        SavingsRepository.Account pea = account(3, SavingsKind.PEA, "0", TODAY.minusYears(2));
        List<SavingsRepository.Position> positions = List.of(
                new SavingsRepository.Position(1, 3, "AI.PA", "Air Liquide", new BigDecimal("10"), new BigDecimal("1500"), new BigDecimal("180")),
                new SavingsRepository.Position(2, 3, "CW8.PA", "ETF MSCI World", new BigDecimal("1"), new BigDecimal("500"), new BigDecimal("520")));
        List<Check> checks = audit(List.of(account(1, SavingsKind.LIVRET_A, "12000", null), pea), positions, "non");
        assertTrue(find(checks, "savings-concentration").title().startsWith("Air Liquide : 78 %"));
        assertTrue(find(checks, "savings-stocks").title().contains("+320"));
        assertTrue(find(checks, "savings-pea-3").detail().contains("5 ans"));
    }

    @Test
    void emptySavingsInviteToFillThem() {
        assertEquals("savings-empty", audit(List.of(), List.of(), "").getFirst().id());
    }
}
