package fr.patria;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Property(
        long id,
        String name,
        String kind,
        String commune,
        String address,
        String insee,
        BigDecimal area,
        BigDecimal purchase,
        LocalDate purchaseDate,
        BigDecimal estimate,
        BigDecimal estimateHigh,
        LocalDate estimateDate,
        BigDecimal saleFeesRate,
        BigDecimal recurringFeesAnnual,
        BigDecimal selectedValue) {

    /** Bien sans valeur retenue : le scenario prudent s'applique. */
    Property(long id, String name, String kind, String commune, String address, String insee, BigDecimal area, BigDecimal purchase,
             LocalDate purchaseDate, BigDecimal estimate, BigDecimal estimateHigh, LocalDate estimateDate, BigDecimal saleFeesRate,
             BigDecimal recurringFeesAnnual) {
        this(id, name, kind, commune, address, insee, area, purchase, purchaseDate, estimate, estimateHigh, estimateDate, saleFeesRate, recurringFeesAnnual, null);
    }

    Property withSelectedValue(BigDecimal value) {
        return new Property(id, name, kind, commune, address, insee, area, purchase, purchaseDate, estimate, estimateHigh, estimateDate, saleFeesRate, recurringFeesAnnual, value);
    }
}
