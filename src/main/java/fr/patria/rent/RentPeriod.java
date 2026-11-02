package fr.patria.rent;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Periode de location a loyer constant.
 *
 * @param label   locataire ou commentaire, facultatif
 * @param end     dernier jour de la periode, {@code null} si le bail est en cours
 * @param monthly loyer mensuel hors charges
 */
public record RentPeriod(long id, long propertyId, String label, LocalDate start, LocalDate end, BigDecimal monthly) {
}
