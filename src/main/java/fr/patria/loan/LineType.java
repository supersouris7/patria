// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.loan;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Type d'une ligne d'echeancier. Le code est celui du fichier CSV et de la base. */
public enum LineType {
    DRAWDOWN("DEBLOCAGE", "Déblocage"),
    INSTALLMENT("ECHEANCE", "Échéance"),
    EARLY_REPAYMENT("REMBOURSEMENT_ANTICIPE", "Remboursement anticipé"),
    CONSOLIDATION("CONSOLIDATION", "Consolidation"),
    ADJUSTMENT("AJUSTEMENT", "Ajustement");

    private final String code;
    private final String label;

    LineType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<LineType> fromCode(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.code.equals(normalized)).findFirst();
    }
}
