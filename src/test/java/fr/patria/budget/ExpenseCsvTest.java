package fr.patria.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.Frequency;
import fr.patria.common.ValidationException;
import fr.patria.household.Person;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpenseCsvTest {
    private static final List<Person> COUPLE = List.of(new Person(1, "William"), new Person(2, "Élodie"));

    @Test
    void readsLabelsCodesFrenchDatesAndShares() {
        String csv = "\uFEFFlibellé;Catégorie;montant;fréquence;début;fin;part_William;part_Elodie\r\n"
                + "Objectif Santé;Santé;35,69;Mensuel;01/01/2026;;;\r\n"
                + "\"Assurance; emprunteur\";CREDIT;1 017,68 €;MONTHLY;2026-01-01;05/09/2042;100;0\r\n";
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(csv, COUPLE, List.of());
        assertEquals(2, rows.size());
        BudgetItem health = rows.get(0).item();
        assertEquals(Category.HEALTH, health.category());
        assertEquals(new BigDecimal("35.69"), health.amount());
        assertEquals(LocalDate.of(2026, 1, 1), health.start());
        assertNull(health.end());
        assertTrue(rows.get(0).shares().isEmpty(), "parts vides : parts égales");
        BudgetItem loan = rows.get(1).item();
        assertEquals("Assurance; emprunteur", loan.label());
        assertEquals(new BigDecimal("1017.68"), loan.amount());
        assertEquals(Frequency.MONTHLY, loan.frequency());
        assertEquals(LocalDate.of(2042, 9, 5), loan.end());
        assertEquals(Map.of(1L, 100, 2L, 0), rows.get(1).shares());
    }

    @Test
    void reportsEachInvalidLineAndFlagsDuplicates() {
        BudgetItem existing = new BudgetItem(9, "Mutuelle", Category.HEALTH, new BigDecimal("35.69"), Frequency.MONTHLY, LocalDate.of(2026, 1, 1), null);
        String csv = "libelle;categorie;montant;frequence;debut;fin\n"
                + "mutuelle;Santé;35.69;Mensuel;2026-01-01;\n"
                + "Gaz;Chauffage;50;Mensuel;2026-01-01;\n"
                + "Eau;Eau et énergie;-3;Mensuel;2026-01-01;\n"
                + "Box;Télécom et abonnements;30;Hebdo;2026-01-01;\n"
                + "Taxe;Impôts et taxes;30;Annuel;31/02/2026;\n"
                + "Auto;Transport;30;Annuel;2026-03-01;2026-01-01\n";
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(csv, List.of(), List.of(existing));
        assertTrue(rows.get(0).valid() && rows.get(0).duplicate());
        assertEquals("Catégorie « Chauffage » inconnue.", rows.get(1).error());
        assertTrue(rows.get(2).error().startsWith("Montant"));
        assertTrue(rows.get(3).error().startsWith("Fréquence"));
        assertTrue(rows.get(4).error().startsWith("Début"));
        assertEquals("La fin précède le début.", rows.get(5).error());
        assertEquals(3, rows.get(1).line());
    }

    @Test
    void sharesMustTotal100() {
        String csv = "libelle;categorie;montant;frequence;debut;part_william;part_élodie\nLoyer;Logement;900;Mensuel;2026-01-01;60;30\n";
        ExpenseCsv.Row row = ExpenseCsv.parse(csv, COUPLE, List.of()).get(0);
        assertFalse(row.valid());
        assertTrue(row.error().contains("100 %"), row.error());
    }

    @Test
    void rejectsUnknownColumnsAndPeople() {
        assertThrows(ValidationException.class, () -> ExpenseCsv.parse("libelle;montant\nX;3\n", COUPLE, List.of()));
        assertThrows(ValidationException.class, () -> ExpenseCsv.parse("libelle;categorie;montant;frequence;debut;couleur\n", COUPLE, List.of()));
        ValidationException unknown = assertThrows(ValidationException.class,
                () -> ExpenseCsv.parse("libelle;categorie;montant;frequence;debut;part_Bob\nX;Santé;3;Mensuel;2026-01-01;100\n", COUPLE, List.of()));
        assertTrue(unknown.getMessage().contains("part_Bob"));
        assertThrows(ValidationException.class, () -> ExpenseCsv.parse("libelle;categorie;montant;frequence;debut\n", COUPLE, List.of()));
    }

    @Test
    void templateIsReadableByTheParser() {
        List<ExpenseCsv.Row> rows = ExpenseCsv.parse(ExpenseCsv.template(COUPLE), COUPLE, List.of());
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(ExpenseCsv.Row::valid));
        assertEquals(Map.of(1L, 100, 2L, 0), rows.get(1).shares());
    }
}
