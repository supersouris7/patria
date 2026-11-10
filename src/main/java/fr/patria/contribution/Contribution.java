// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.contribution;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Apport personnel verse pour un bien. */
public record Contribution(long id, long propertyId, String name, BigDecimal amount, LocalDate date) {
}
