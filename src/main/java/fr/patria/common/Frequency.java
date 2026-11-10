// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Rythme d'un revenu ou d'une depense. Le mois du premier versement fixe le cycle : une depense annuelle
 * commencee en octobre revient chaque octobre, une trimestrielle commencee en janvier revient en avril, juillet...
 */
public enum Frequency {
    MONTHLY("Mensuel", 1),
    QUARTERLY("Trimestriel", 3),
    SEMIANNUAL("Semestriel", 6),
    ANNUAL("Annuel", 12),
    ONCE("Ponctuel", 0);

    private final String label;
    private final int months;

    Frequency(String label, int months) {
        this.label = label;
        this.months = months;
    }

    public String label() {
        return label;
    }

    /** Vrai si un versement a lieu le mois {@code month}, pour une periode commencant en {@code first} (fin incluse, facultative). */
    public boolean occursIn(YearMonth first, YearMonth last, YearMonth month) {
        if (month.isBefore(first) || (last != null && month.isAfter(last))) return false;
        if (this == ONCE) return month.equals(first);
        return ChronoUnit.MONTHS.between(first, month) % months == 0;
    }

    /** Equivalent mensuel moyen (0 pour un versement ponctuel). */
    public BigDecimal monthly(BigDecimal amount) {
        return this == ONCE ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
    }

    public boolean isRecurring() {
        return this != ONCE;
    }

    public static Frequency parse(String value) {
        return Arrays.stream(values()).filter(frequency -> frequency.name().equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Fréquence invalide."));
    }
}
