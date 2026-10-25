package fr.patria;

import java.math.BigDecimal;
import java.time.LocalDate;

record ScheduleLine(LocalDate date, String type, BigDecimal amount, BigDecimal principalRepaid,
                    BigDecimal interest, BigDecimal fees, BigDecimal remainingPrincipal, BigDecimal annualRate) {
}
