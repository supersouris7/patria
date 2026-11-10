// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.Frequency;
import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseCsvTest {
    @Test
    void readsHeaderlessLinesWithLabelsCodesAndFrenchDates() {
        String csv = "﻿Objectif Santé;Santé;35,69;Mensuel;01/01/2026;\r\n"
                + "\"Assurance; emprunteur\";CREDIT;1 017,68 €;MONTHLY;2026-01-01;05/09/2042\r\n"
                + "Protection juridique;assurances;26.40;annuel;1/12/2025\r\n";
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(csv, List.of());
        assertEquals(3, rows.size());
        BudgetItem health = rows.get(0).item();
        assertEquals(1, rows.get(0).line());
        assertEquals(Category.HEALTH, health.category());
        assertEquals(new BigDecimal("35.69"), health.amount());
        assertEquals(LocalDate.of(2026, 1, 1), health.start());
        assertNull(health.end());
        BudgetItem loan = rows.get(1).item();
        assertEquals("Assurance; emprunteur", loan.label());
        assertEquals(new BigDecimal("1017.68"), loan.amount());
        assertEquals(Frequency.MONTHLY, loan.frequency());
        assertEquals(LocalDate.of(2042, 9, 5), loan.end());
        assertEquals(Frequency.ANNUAL, rows.get(2).item().frequency(), "fin facultative : 5 colonnes acceptées");
    }

    @Test
    void reportsEachInvalidLineAndFlagsDuplicates() {
        BudgetItem existing = new BudgetItem(9, "Mutuelle", Category.HEALTH, new BigDecimal("35.69"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null);
        String csv = "mutuelle;Santé;35.69;Mensuel;2026-01-01;\n"
                + "Gaz;Chauffage;50;Mensuel;2026-01-01;\n"
                + "Eau;Eau et énergie;-3;Mensuel;2026-01-01;\n"
                + "Box;Télécom et abonnements;30;Hebdo;2026-01-01;\n"
                + "Taxe;Impôts et taxes;30;Annuel;31/02/2026;\n"
                + "Auto;Transport;30;Annuel;2026-03-01;2026-01-01\n"
                + "Trop;Santé;3;Mensuel;2026-01-01;;Abonnement;100\n";
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(csv, List.of(existing));
        assertTrue(rows.get(0).valid() && rows.get(0).duplicate());
        assertEquals("Catégorie « Chauffage » inconnue.", rows.get(1).error());
        assertEquals(2, rows.get(1).line());
        assertTrue(rows.get(2).error().startsWith("Montant"));
        assertTrue(rows.get(3).error().startsWith("Fréquence"));
        assertTrue(rows.get(4).error().startsWith("Début"), "31 février refusé");
        assertEquals("La fin précède le début.", rows.get(5).error());
        assertTrue(rows.get(6).error().startsWith("8 colonnes"));
    }

    @Test
    void refusesAHeaderLine() {
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse("libelle;categorie;montant;frequence;debut;fin\nMutuelle;Santé;35,69;Mensuel;01/01/2026;\n", List.of());
        assertTrue(rows.get(0).error().contains("en-tête"), rows.get(0).error());
        assertTrue(rows.get(1).valid());
        assertThrows(ValidationException.class, () -> ExpenseCsv.parse("\n\n", List.of()));
    }

    @Test
    void templateIsReadableByTheParser() {
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(ExpenseCsv.template(), List.of());
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(ExpenseCsv.Row::valid));
    }

    @Test
    void readsOptionalHintsAndLeavesPossibleDuplicatesUnchecked() {
        String csv = "Forfait mobile;Télécom et abonnements;19,99;Mensuel;05/01/2026;;abonnement, TELECOM\n"
                + "Disney+;Télécom et abonnements;9,99;Mensuel;12/01/2026;;Streaming,Doublon possible\n"
                + "Mutuelle;Santé;35,69;Mensuel;01/01/2026;\n";
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(csv, List.of());
        assertEquals(List.of("Abonnement", "Télécom"), rows.get(0).hints(), "repères ramenés à la liste connue");
        assertTrue(rows.get(0).suggested());
        assertTrue(!rows.get(1).suggested(), "doublon possible : décoché d'avance");
        assertEquals(List.of(), rows.get(2).hints(), "6 colonnes toujours acceptées");
        assertTrue(ExpenseCsv.llmPrompt().contains("ÉTAPE 1") && ExpenseCsv.llmPrompt().contains("Doublon possible"));
    }
}
