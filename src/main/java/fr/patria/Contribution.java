package fr.patria;

import java.math.BigDecimal;

record Contribution(long id, long propertyId, String name, BigDecimal amount) {
}
