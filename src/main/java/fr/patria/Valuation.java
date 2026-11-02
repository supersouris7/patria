package fr.patria;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Scenarios d'estimation et valeur nette, calcules de la meme facon cote serveur et cote navigateur. */
final class Valuation {
    static final BigDecimal DEFAULT_SALE_FEES = new BigDecimal("0.08");
    static final BigDecimal PESSIMISTIC_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal STEP = new BigDecimal("10000");

    private Valuation() {
    }

    record Scenarios(BigDecimal pessimistic, BigDecimal prudent, BigDecimal middle, BigDecimal optimistic) {
        boolean isEmpty() {
            return optimistic.signum() == 0;
        }
    }

    static BigDecimal prudent(Property property) {
        return property.estimate().signum() == 0 ? property.purchase() : property.estimate();
    }

    static BigDecimal optimistic(Property property) {
        return property.estimateHigh().signum() == 0 ? prudent(property) : property.estimateHigh().max(prudent(property));
    }

    static BigDecimal saleFeesRate(Property property) {
        return property.saleFeesRate() == null ? DEFAULT_SALE_FEES : property.saleFeesRate();
    }

    static Scenarios of(List<Property> properties) {
        BigDecimal prudent = properties.stream().map(Valuation::prudent).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal optimistic = properties.stream().map(Valuation::optimistic).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Scenarios(round(prudent.multiply(PESSIMISTIC_FACTOR)), round(prudent),
                round(prudent.add(optimistic).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)), round(optimistic));
    }

    /** Taux de frais de vente pondere par la valeur prudente de chaque bien. */
    static BigDecimal saleFeesRate(List<Property> properties) {
        BigDecimal weight = properties.stream().map(Valuation::prudent).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weight.signum() == 0) return properties.isEmpty() ? DEFAULT_SALE_FEES
                : properties.stream().map(Valuation::saleFeesRate).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(properties.size()), 6, RoundingMode.HALF_UP);
        BigDecimal fees = properties.stream().map(property -> prudent(property).multiply(saleFeesRate(property))).reduce(BigDecimal.ZERO, BigDecimal::add);
        return fees.divide(weight, 6, RoundingMode.HALF_UP);
    }

    /** Valeur nette indicative = valeur - frais de vente/fiscalite - capital restant du. */
    static BigDecimal net(BigDecimal value, BigDecimal saleFeesRate, BigDecimal debt) {
        return value.subtract(value.multiply(saleFeesRate)).subtract(debt).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal round(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        return value.divide(STEP, 0, RoundingMode.HALF_UP).multiply(STEP);
    }
}
