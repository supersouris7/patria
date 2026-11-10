// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.patria.budget.ExpenseCsvTestAccess;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InputRulesTest {
    @Test
    void numbersAreWrittenSimply() {
        assertEquals(0, new BigDecimal("1250.5").compareTo(InputRules.decimal("1250.5")));
        assertEquals(0, new BigDecimal("-5").compareTo(InputRules.decimal("-5")));
        assertEquals(0, new BigDecimal("0.5").compareTo(InputRules.decimal(".5")));
        for (String odd : new String[]{"1e5", "1E-400", "0x10", "1..2", "NaN", "Infinity", "+5", "1.", "", "１２３", "12.345.67"}) {
            assertNull(InputRules.decimal(odd), odd);
        }
    }

    @Test
    void datesStayBetween1900And2100() {
        assertEquals(LocalDate.of(2026, 1, 1), InputRules.checkDate(LocalDate.of(2026, 1, 1), "Début"));
        assertThrows(ValidationException.class, () -> InputRules.checkDate(LocalDate.of(1, 1, 1), "Début"));
        assertThrows(ValidationException.class, () -> InputRules.checkDate(LocalDate.of(20260, 1, 1), "Début"));
    }

    @Test
    void textWithoutControlCharacters() {
        assertEquals("Électricité", InputRules.checkText("Électricité", "Libellé"));
        assertThrows(ValidationException.class, () -> InputRules.checkText("Loyer\nmars", "Libellé"));
        assertThrows(ValidationException.class, () -> InputRules.checkText("a\u0000b", "Libellé"));
    }

    @Test
    void csvImportsRefuseHugeAmountsAndOddDates() {
        assertEquals("Montant « 1e17 » invalide (nombre positif, 2 décimales au plus).", ExpenseCsvTestAccess.error("Test;Autres;1e17;Mensuel;01/01/2026;"));
        assertEquals("Montant « 2000000000 » trop élevé (un milliard au plus).", ExpenseCsvTestAccess.error("Test;Autres;2000000000;Mensuel;01/01/2026;"));
        assertEquals("Début : date hors limites (entre 1900 et 2100).", ExpenseCsvTestAccess.error("Test;Autres;10;Mensuel;01/01/0001;"));
        String header = "date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct\n";
        assertThrows(ValidationException.class, () -> fr.patria.loan.CsvScheduleParser.parse((header + "2026-01-01,ECHEANCE,1e30,,,,,3\n").getBytes()), "notation scientifique");
        assertThrows(ValidationException.class, () -> fr.patria.loan.CsvScheduleParser.parse((header + "2026-01-01,ECHEANCE,100,,,,,320\n").getBytes()), "taux de 320 %");
        assertThrows(ValidationException.class, () -> fr.patria.loan.CsvScheduleParser.parse((header + "0201-01-01,ECHEANCE,100,,,,,3\n").getBytes()), "an 201");
    }
}
