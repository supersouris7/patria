// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.loan;

/** Pret rattache a un bien. Tous ses chiffres sont calcules a partir de son echeancier ({@link LoanMath}). */
public record Loan(long id, long propertyId, String name) {
}
