package fr.patria.household;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.Frequency;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncomeCsvTest {
    @Test
    void readsIncomesForAccessibleMembersOnly() {
        List<Person> people = List.of(new Person(1, "Paul", Person.Kind.PERSON), new Person(2, "Élodie", Person.Kind.PERSON));
        List<IncomeCsv.Row> rows = IncomeCsv.parse("""
                Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;
                elodie;Prime de fin d'année;Prime;1200;Annuel;01/12/2026;
                Inconnu;Salaire;Salaire net;100;Mensuel;01/01/2026;
                Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;
                """, people, List.of());
        assertEquals(new BigDecimal("2450.00"), rows.get(0).income().amount());
        assertEquals(IncomeKind.BONUS, rows.get(1).income().kind());
        assertEquals(2, rows.get(1).income().personId(), "prénom sans accent ni majuscule reconnu");
        assertEquals(Frequency.ANNUAL, rows.get(1).income().frequency());
        assertTrue(rows.get(2).error().contains("inconnu"));
        assertTrue(rows.get(3).duplicate(), "même revenu deux fois");
        assertTrue(IncomeCsv.llmPrompt(List.of("Paul", "Élodie")).contains("Paul, Élodie"));
    }
}
