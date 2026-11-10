// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.household;

import fr.patria.common.Frequency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Revenu d'une personne du foyer, net avant impot.
 *
 * @param start premier versement : son mois fixe le cycle (une prime annuelle versee en decembre revient chaque decembre)
 * @param end   dernier versement possible, {@code null} si sans fin (changement de salaire : terminer et en saisir un nouveau)
 */
public record Income(long id, long personId, String label, IncomeKind kind, BigDecimal amount, Frequency frequency, LocalDate start, LocalDate end) {
    public boolean paidIn(YearMonth month) {
        return frequency.occursIn(YearMonth.from(start), end == null ? null : YearMonth.from(end), month);
    }

    /** Vrai si le revenu est en cours a cette date. */
    public boolean activeOn(LocalDate date) {
        return !start.isAfter(date) && (end == null || !end.isBefore(date));
    }
}
