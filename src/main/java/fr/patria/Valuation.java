package fr.patria;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Scenarios d'estimation et valeur nette, calcules de la meme facon cote serveur et cote navigateur. */
final class Valuation {
    static final BigDecimal DEFAULT_SALE_FEES = new BigDecimal("0.08");
    static final BigDecimal PESSIMISTIC_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal STEP = new BigDecimal("10000");
    private static final BigDecimal SLIDER_MARGIN = new BigDecimal("50000");

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

    static Scenarios of(Property property) {
        BigDecimal prudent = prudent(property);
        BigDecimal optimistic = optimistic(property);
        return new Scenarios(round(prudent.multiply(PESSIMISTIC_FACTOR)), round(prudent),
                round(prudent.add(optimistic).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)), round(optimistic));
    }

    /** Valeur retenue avec le curseur du bien ; a defaut, le scenario prudent. */
    static BigDecimal retained(Property property) {
        return property.selectedValue() != null ? property.selectedValue() : of(property).prudent();
    }

    /** Nom du scenario correspondant a la valeur retenue, ou « Personnalisée ». */
    static String scenarioName(Property property) {
        Scenarios s = of(property);
        BigDecimal value = retained(property);
        if (value.compareTo(s.pessimistic()) == 0) return "Pessimiste";
        if (value.compareTo(s.prudent()) == 0) return "Prudent";
        if (value.compareTo(s.middle()) == 0) return "Fourchette";
        if (value.compareTo(s.optimistic()) == 0) return "Optimiste";
        return "Personnalisée";
    }

    /** Bornes du curseur : 50 000 EUR de marge autour des scenarios extremes. */
    static BigDecimal sliderMin(Scenarios s) {
        return s.pessimistic().subtract(SLIDER_MARGIN).max(BigDecimal.ZERO);
    }

    static BigDecimal sliderMax(Scenarios s) {
        return s.optimistic().add(SLIDER_MARGIN);
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
