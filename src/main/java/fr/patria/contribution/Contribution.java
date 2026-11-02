package fr.patria.contribution;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Apport personnel verse pour un bien. */
public record Contribution(long id, long propertyId, String name, BigDecimal amount, LocalDate date) {
}
