package fr.patria.budget;

import fr.patria.common.Frequency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Depense recurrente ou ponctuelle saisie a la main (facture, abonnement, assurance...).
 *
 * @param start premier paiement : son mois fixe le cycle (assurance annuelle payee en mars : chaque mars)
 * @param end   dernier paiement possible, {@code null} si sans fin
 */
public record BudgetItem(long id, String label, Category category, BigDecimal amount, Frequency frequency, LocalDate start, LocalDate end) {
    public boolean dueIn(YearMonth month) {
        return frequency.occursIn(YearMonth.from(start), end == null ? null : YearMonth.from(end), month);
    }
}
