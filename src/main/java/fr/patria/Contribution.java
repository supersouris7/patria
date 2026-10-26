package fr.patria;

import java.math.BigDecimal;
import java.time.LocalDate;

record Contribution(long id, long propertyId, String name, BigDecimal amount, LocalDate date) {
}
