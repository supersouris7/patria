// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
/**
 * Module « charges » : taxe fonciere, charges de copropriete, assurance... saisies par annee. Les annees non saisies
 * sont estimees ({@link fr.patria.expense.ExpenseMath}) : il suffit d'un avis pour couvrir toute la detention.
 * Depend uniquement du module « biens ».
 */
package fr.patria.expense;
