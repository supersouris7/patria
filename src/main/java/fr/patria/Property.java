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
        LocalDate estimateDate) {
}
