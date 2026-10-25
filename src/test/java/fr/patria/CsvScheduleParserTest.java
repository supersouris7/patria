package fr.patria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvScheduleParserTest {
    @Test
    void acceptsBomSemicolonAndFrenchNumbers() {
        String csv = "\uFEFFdate;type;montant;capital_restant_du\n2026-01-05;echeance;1 250,50;9 500,00\n";

        ScheduleLine line = CsvScheduleParser.parse(csv.getBytes(StandardCharsets.UTF_8)).getFirst();

        assertEquals(new BigDecimal("1250.50"), line.amount());
        assertEquals(new BigDecimal("9500.00"), line.remainingPrincipal());
    }

    @Test
    void rejectsRowsWithoutFinancialValues() {
        byte[] csv = "date,type\n2026-01-05,echeance\n".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> CsvScheduleParser.parse(csv));
    }

    @Test
    void acceptsBankScheduleTypesIgnoringCase() {
        byte[] csv = "date,type,montant,capital_restant_du\n2026-01-05,DEBLOCAGE,1000,1000\n2026-02-05,consolidation,,1000\n"
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("DEBLOCAGE", CsvScheduleParser.parse(csv).getFirst().type());
        assertEquals("CONSOLIDATION", CsvScheduleParser.parse(csv).getLast().type());
    }
}
