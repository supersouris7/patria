package fr.patria.market;

import fr.patria.property.PropertyKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Vente d'un logement unique (appartement ou maison) issue de DVF ; {@code address} peut etre vide. */
record Sale(LocalDate date, BigDecimal price, BigDecimal area, PropertyKind kind, double latitude, double longitude, String address) {
    BigDecimal pricePerSquareMeter() {
        return price.divide(area, 2, RoundingMode.HALF_UP);
    }
}
