package fr.patria.property;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Bien immobilier.
 *
 * @param area           surface en m², {@code null} si inconnue
 * @param purchase       cout total d'acquisition (prix, frais, travaux), 0 si inconnu
 * @param estimateLow    estimation prudente, {@code null} si absente (le cout d'acquisition est alors utilise)
 * @param estimateHigh   estimation optimiste, {@code null} si absente (egale a l'estimation prudente)
 * @param saleFeesRate   frais de vente et fiscalite en ratio (0.08 = 8 %)
 * @param selectedValue  valeur retenue avec le curseur, {@code null} = scenario prudent
 */
public record Property(
        long id,
        String name,
        PropertyKind kind,
        String commune,
        String address,
        BigDecimal area,
        BigDecimal purchase,
        LocalDate purchaseDate,
        BigDecimal estimateLow,
        BigDecimal estimateHigh,
        LocalDate estimateDate,
        BigDecimal saleFeesRate,
        BigDecimal selectedValue) {

    public Property withId(long newId) {
        return new Property(newId, name, kind, commune, address, area, purchase, purchaseDate, estimateLow, estimateHigh, estimateDate, saleFeesRate, selectedValue);
    }

    public Property withSelectedValue(BigDecimal value) {
        return new Property(id, name, kind, commune, address, area, purchase, purchaseDate, estimateLow, estimateHigh, estimateDate, saleFeesRate, value);
    }
}
