package fr.patria.household;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SharesTest {
    private static final List<Person> PEOPLE = List.of(new Person(1, "Alice"), new Person(2, "Bob"));

    @Test
    void emptyMeansEqualPartsIncludingPeopleAddedLater() {
        assertEquals(0, new BigDecimal("0.5").compareTo(Shares.weight(Map.of(), PEOPLE, 1)));
        List<Person> three = List.of(new Person(1, "Alice"), new Person(2, "Bob"), new Person(3, "Chloé"));
        assertEquals(0, new BigDecimal("0.333333").compareTo(Shares.weight(Map.of(), three, 3)));
        assertEquals("Parts égales", Shares.describe(Map.of(), PEOPLE));
    }

    @Test
    void jointAccountTakesPartOnlyWhenGivenAPercentage() {
        List<Person> members = List.of(new Person(1, "Alice"), new Person(2, "Bob"), new Person(3, "Compte joint", Person.Kind.ACCOUNT));
        assertEquals(0, new BigDecimal("0.5").compareTo(Shares.weight(Map.of(), members, 1)), "parts égales entre les personnes");
        assertEquals(0, BigDecimal.ZERO.compareTo(Shares.weight(Map.of(), members, 3)), "le compte n'y prend pas part");
        assertEquals(0, BigDecimal.ONE.compareTo(Shares.weight(Map.of(3L, 100), members, 3)));
        assertEquals("Compte joint 100\u00a0%", Shares.describe(Map.of(1L, 0, 2L, 0, 3L, 100), members));
    }

    @Test
    void customPartsAreNormalisedOverRemainingPeople() {
        assertEquals(0, new BigDecimal("0.6").compareTo(Shares.weight(Map.of(1L, 60, 2L, 40), PEOPLE, 1)));
        assertEquals("Alice 60 % · Bob 40 %", Shares.describe(Map.of(1L, 60, 2L, 40), PEOPLE));
        assertEquals("Bob 100 %", Shares.describe(Map.of(1L, 0, 2L, 100), PEOPLE));
        // la part d'une personne supprimee revient aux autres
        assertEquals(0, BigDecimal.ONE.compareTo(Shares.weight(Map.of(1L, 60, 2L, 40), List.of(new Person(2, "Bob")), 2)));
    }

    @Test
    void formMustTotalOneHundredUnlessLeftEmpty() {
        assertTrue(Shares.parse(Map.of("share_1", "", "share_2", " "), PEOPLE).isEmpty());
        assertEquals(Map.of(1L, 70, 2L, 30), Shares.parse(Map.of("share_1", "70", "share_2", "30 %"), PEOPLE));
        assertEquals(Map.of(1L, 100, 2L, 0), Shares.parse(Map.of("share_1", "100"), PEOPLE));
        ValidationException error = assertThrows(ValidationException.class, () -> Shares.parse(Map.of("share_1", "70", "share_2", "20"), PEOPLE));
        assertTrue(error.getMessage().contains("90 %"));
        assertThrows(ValidationException.class, () -> Shares.parse(Map.of("share_1", "abc"), PEOPLE));
    }
}
