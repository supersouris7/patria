// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.expense;

import java.math.BigDecimal;

/** Montant annuel d'une charge, saisi d'apres un avis ou un releve (une seule valeur par type et par annee). */
public record Expense(long id, long propertyId, ExpenseKind kind, int year, BigDecimal amount) {
}
