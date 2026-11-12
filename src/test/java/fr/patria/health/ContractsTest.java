// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static org.junit.jupiter.api.Assertions.*;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.Category;
import fr.patria.common.Frequency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    private static BudgetItem item(long id, String label, Category category, String amount, Frequency frequency) {
        return new BudgetItem(id, label, category, new BigDecimal(amount), frequency, LocalDate.of(2026, 1, 1), null);
    }

    @Test
    void recognisesContractsAndEstimatesACautiousGain() {
        List<BudgetItem> items = List.of(
                item(1, "Box fibre Orange", Category.TELECOM, "49.99", Frequency.MONTHLY),
                item(2, "Forfait mobile", Category.TELECOM, "24.99", Frequency.MONTHLY),
                item(3, "Frais bancaires", Category.OTHER, "8", Frequency.MONTHLY),
                item(4, "Assurance habitation", Category.INSURANCE, "540", Frequency.ANNUAL),
                item(5, "Péage autoroute", Category.TRANSPORT, "40", Frequency.MONTHLY),
                item(6, "Electricité EDF", Category.ENERGY, "95", Frequency.MONTHLY));
        List<Contracts.Contract> found = Contracts.find(items, TODAY, 45);
        Contracts.Contract box = found.stream().filter(contract -> contract.kind() == Contracts.Kind.INTERNET).findFirst().orElseThrow();
        assertEquals(new BigDecimal("240"), box.yearlyGain(), "(49,99 − 30) × 12, arrondi");
        assertTrue(found.stream().anyMatch(contract -> contract.kind() == Contracts.Kind.BANK && contract.yearlyGain().compareTo(new BigDecimal("96")) == 0),
                "frais bancaires : tout est évitable");
        Contracts.Contract home = found.stream().filter(contract -> contract.kind() == Contracts.Kind.HOME_INSURANCE).findFirst().orElseThrow();
        assertEquals(new BigDecimal("45.00"), home.monthly(), "montant annuel ramené au mois");
        assertTrue(found.stream().noneMatch(contract -> contract.kind() == Contracts.Kind.CAR_INSURANCE), "« autoroute » n'est pas une assurance auto");
        Contracts.Contract power = found.stream().filter(contract -> contract.kind() == Contracts.Kind.ELECTRICITY).findFirst().orElseThrow();
        assertFalse(power.above(), "l'électricité n'a pas de gain chiffré : on compare sur le comparateur officiel");
        assertEquals("https://comparateur.energie-info.fr", power.link());
    }

    @Test
    void endedExpensesAreNotContracts() {
        BudgetItem ended = new BudgetItem(1, "Box internet", Category.TELECOM, new BigDecimal("40"), Frequency.MONTHLY, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 3, 31));
        assertTrue(Contracts.find(List.of(ended), TODAY, null).isEmpty());
    }

    @Test
    void mutualReferenceRisesWithAge() {
        assertTrue(Contracts.mutualReference(25) < Contracts.mutualReference(45));
        assertTrue(Contracts.mutualReference(45) < Contracts.mutualReference(72));
    }

    @Test
    void scoreAndChallengeStaySimple() {
        assertEquals(0, EconomiesController.score(0, 0, false, false, 0, BigDecimal.ZERO).value(), "rien de saisi : 0");
        EconomiesController.Score full = EconomiesController.score(0, 0, true, true, 5, BigDecimal.valueOf(6));
        assertEquals(100, full.value());
        assertEquals("Excellente", full.level());
        assertEquals(78, EconomiesController.score(1, 1, true, true, 2, BigDecimal.valueOf(6)).value(), "40 − 12 − 4 + 24 + 30");
        Contracts.Contract box = Contracts.assess(Contracts.Kind.INTERNET, List.of(item(1, "Box", Category.TELECOM, "50", Frequency.MONTHLY)), new BigDecimal("50"), null);
        EconomiesController.Challenge challenge = EconomiesController.challenge(List.of(box), List.of(), BigDecimal.ONE, true, TODAY);
        assertTrue(challenge.title().contains("box internet") && challenge.href().equals("#contrat-internet"), "le contrat au plus gros gain");
        EconomiesRepository.Economy done = new EconomiesRepository.Economy(1, "INTERNET", "Box internet", new BigDecimal("50"), new BigDecimal("25"), TODAY);
        assertTrue(EconomiesController.challenge(List.of(box), List.of(done), BigDecimal.valueOf(4), true, TODAY).done(), "renégocié ce mois-ci : défi réussi");
    }
}
