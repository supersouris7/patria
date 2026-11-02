package fr.patria.loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne d'echeancier importee. Seuls la date et le type sont obligatoires ; les montants absents valent {@code null}.
 *
 * @param annualRate taux annuel en pourcentage (3.2 = 3,2 %)
 */
public record ScheduleLine(LocalDate date, LineType type, BigDecimal amount, BigDecimal principalRepaid,
                           BigDecimal interest, BigDecimal fees, BigDecimal remainingPrincipal, BigDecimal annualRate) {
}
