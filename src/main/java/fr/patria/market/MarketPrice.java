// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Prix au m² du marche autour d'un bien, calcule a partir des ventes DVF.
 *
 * @param fingerprint  donnees du bien ayant servi au calcul (type, surface, adresse) : un changement relance le calcul
 * @param median       prix median au m², actualise
 * @param low          premier quartile (25 % des ventes en dessous)
 * @param high         troisieme quartile (75 % des ventes en dessous)
 * @param period       annees de ventes utilisees, par exemple « 2023–2025 »
 * @param indexMonth   mois de reference de l'actualisation, {@code null} si aucune
 * @param revaluation  evolution des prix du departement depuis la date d'estimation du bien (1,05 = +5 %),
 *                     {@code null} si l'indice n'est pas disponible
 */
public record MarketPrice(long propertyId, String fingerprint, LocalDate computedOn, Status status, BigDecimal median,
                          BigDecimal low, BigDecimal high, int salesCount, Scope scope, String place, String period,
                          YearMonth indexMonth, String message, BigDecimal revaluation) {

    MarketPrice withRevaluation(BigDecimal factor) {
        return new MarketPrice(propertyId, fingerprint, computedOn, status, median, low, high, salesCount, scope, place, period, indexMonth, message, factor);
    }

    public enum Status {
        /** Prix calcule. */
        OK,
        /** Trop peu de ventes comparables. */
        INSUFFICIENT,
        /** Pas de donnees pour ce lieu (Alsace-Moselle, Mayotte, adresse introuvable...). */
        UNAVAILABLE,
        /** Service data.gouv.fr injoignable : nouvel essai le lendemain. */
        ERROR,
        /** Calcul en cours en arriere-plan (jamais enregistre). */
        PENDING
    }

    /** Zone des ventes retenues. */
    public enum Scope {
        RADIUS_500("dans un rayon de 500 m", 500),
        RADIUS_1000("dans un rayon de 1 km", 1000),
        RADIUS_2000("dans un rayon de 2 km", 2000),
        COMMUNE("dans la commune", 0);

        private final String label;
        private final double meters;

        Scope(String label, double meters) {
            this.label = label;
            this.meters = meters;
        }

        double meters() {
            return meters;
        }

        public String label() {
            return label;
        }
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isAvailable() {
        return status == Status.OK;
    }

    static MarketPrice failure(long propertyId, String fingerprint, LocalDate today, Status status, String message) {
        return new MarketPrice(propertyId, fingerprint, today, status, null, null, null, 0, null, null, null, null, message, null);
    }
}
