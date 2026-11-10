// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import java.util.List;

/** Acces de test a la lecture d'une ligne d'import CSV : message d'erreur de la premiere ligne. */
public final class ExpenseCsvTestAccess {
    private ExpenseCsvTestAccess() {
    }

    public static String error(String line) {
        return ExpenseCsv.parse(line, List.of()).getFirst().error();
    }
}
