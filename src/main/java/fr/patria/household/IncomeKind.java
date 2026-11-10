// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.household;

import fr.patria.common.ValidationException;
import java.util.Arrays;

/** Nature d'un revenu. Le nom de la constante est stocke en base. */
public enum IncomeKind {
    SALARY("Salaire net"),
    BONUS("Prime, 13e mois"),
    OTHER("Autre revenu");

    private final String label;

    IncomeKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static IncomeKind parse(String value) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Type de revenu invalide."));
    }
}
