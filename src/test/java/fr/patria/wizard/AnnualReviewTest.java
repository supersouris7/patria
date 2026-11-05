package fr.patria.wizard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.common.Frequency;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnualReviewTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Test
    void nextPaymentKeepsTheRhythm() {
        assertEquals(LocalDate.of(2026, 11, 5), AnnualReview.nextOccurrence(LocalDate.of(2026, 1, 5), Frequency.MONTHLY, TODAY));
        assertEquals(LocalDate.of(2027, 3, 10), AnnualReview.nextOccurrence(LocalDate.of(2026, 3, 10), Frequency.SEMIANNUAL, TODAY));
        assertEquals(LocalDate.of(2026, 12, 1), AnnualReview.nextOccurrence(LocalDate.of(2024, 12, 1), Frequency.ANNUAL, TODAY));
        assertEquals(LocalDate.of(2027, 2, 1), AnnualReview.nextOccurrence(LocalDate.of(2027, 2, 1), Frequency.MONTHLY, TODAY), "pas encore commencée");
    }

    @Test
    void reviewIsDueAfterOneYear() {
        assertTrue(AnnualReview.isDue(LocalDate.of(2025, 10, 1), null, null, TODAY));
        assertFalse(AnnualReview.isDue(LocalDate.of(2026, 1, 1), null, null, TODAY));
        assertFalse(AnnualReview.isDue(null, LocalDate.of(2026, 6, 1), LocalDate.of(2020, 1, 1), TODAY), "guide utilisé il y a moins d'un an");
        assertTrue(AnnualReview.isDue(null, null, LocalDate.of(2024, 1, 1), TODAY), "données anciennes, jamais revues");
        assertFalse(AnnualReview.isDue(null, null, null, TODAY), "rien à revoir");
        assertEquals("Avis de taxe foncière de chaque bien.", AnnualReview.thisMonth(LocalDate.of(2026, 10, 15)).what());
    }
}
