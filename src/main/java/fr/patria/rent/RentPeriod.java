package fr.patria.rent;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Periode d'occupation a montant mensuel constant.
 *
 * @param kind    location (loyer percu) ou residence principale (loyer de reference, economise)
 * @param label   locataire ou commentaire, facultatif
 * @param end     dernier jour de la periode, {@code null} si elle est en cours
 * @param monthly loyer mensuel hors charges (percu ou de reference)
 */
public record RentPeriod(long id, long propertyId, Occupancy kind, String label, LocalDate start, LocalDate end, BigDecimal monthly) {
}
