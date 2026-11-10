// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.expense;

import fr.patria.common.ValidationException;
import java.util.Arrays;

/** Nature d'une charge annuelle. Le nom de la constante est stocke en base. */
public enum ExpenseKind {
    PROPERTY_TAX("Taxe foncière", "revalorisée chaque année selon le coefficient légal"),
    /** Due seulement hors residence principale (supprimee pour les residences principales depuis 2023). */
    HOUSING_TAX("Taxe d'habitation", "montant constant entre deux saisies (résidence secondaire ou logement vacant)"),
    CONDO_FEES("Charges de copropriété", "montant constant entre deux saisies"),
    INSURANCE("Assurance", "montant constant entre deux saisies"),
    OTHER("Autre", "montant constant entre deux saisies");

    private final String label;
    private final String estimation;

    ExpenseKind(String label, String estimation) {
        this.label = label;
        this.estimation = estimation;
    }

    public String label() {
        return label;
    }

    /** Regle utilisee pour estimer les annees non saisies. */
    public String estimation() {
        return estimation;
    }

    public static ExpenseKind parse(String value) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Type de charge invalide."));
    }
}
