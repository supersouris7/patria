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
 */
public record MarketPrice(long propertyId, String fingerprint, LocalDate computedOn, Status status, BigDecimal median,
                          BigDecimal low, BigDecimal high, int salesCount, Scope scope, String place, String period,
                          YearMonth indexMonth, String message) {

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
        RADIUS_500("dans un rayon de 500 m"),
        RADIUS_1000("dans un rayon de 1 km"),
        COMMUNE("dans la commune");

        private final String label;

        Scope(String label) {
            this.label = label;
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
        return new MarketPrice(propertyId, fingerprint, today, status, null, null, null, 0, null, null, null, null, message);
    }
}
