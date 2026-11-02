package fr.patria.property;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Valeur d'un bien. Quatre scenarios arrondis a 10 000 € servent de reperes au curseur :
 * <ul>
 *   <li>prudent : estimation prudente, a defaut le cout d'acquisition ;</li>
 *   <li>optimiste : estimation optimiste, a defaut la prudente ;</li>
 *   <li>fourchette : milieu des deux ;</li>
 *   <li>pessimiste : prudent − 5 %.</li>
 * </ul>
 * La valeur retenue est celle choisie avec le curseur, sinon le scenario prudent.
 * La meme formule de valeur nette est reprise dans {@code app.js} pour l'affichage en direct.
 */
public final class Valuation {
    public static final BigDecimal DEFAULT_SALE_FEES = new BigDecimal("0.08");
    private static final BigDecimal PESSIMISTIC_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal STEP = new BigDecimal("10000");
    private static final BigDecimal SLIDER_MARGIN = new BigDecimal("50000");

    private Valuation() {
    }

    public record Scenarios(BigDecimal pessimistic, BigDecimal prudent, BigDecimal middle, BigDecimal optimistic) {
        public boolean isEmpty() {
            return optimistic.signum() == 0;
        }

        /** Nom du scenario egal a {@code value}, ou « Personnalisée ». */
        public String nameOf(BigDecimal value) {
            if (value.compareTo(pessimistic) == 0) return "Pessimiste";
            if (value.compareTo(prudent) == 0) return "Prudent";
            if (value.compareTo(middle) == 0) return "Fourchette";
            if (value.compareTo(optimistic) == 0) return "Optimiste";
            return "Personnalisée";
        }

        public BigDecimal sliderMin() {
            return pessimistic.subtract(SLIDER_MARGIN).max(BigDecimal.ZERO);
        }

        public BigDecimal sliderMax() {
            return optimistic.add(SLIDER_MARGIN);
        }

        /** Ramene une valeur sur la graduation du curseur (pas de 10 000 €, dans les bornes). */
        public BigDecimal clamp(BigDecimal value) {
            return round(value).max(sliderMin()).min(sliderMax());
        }

        boolean sameAs(Scenarios other) {
            return pessimistic.compareTo(other.pessimistic) == 0 && prudent.compareTo(other.prudent) == 0
                    && middle.compareTo(other.middle) == 0 && optimistic.compareTo(other.optimistic) == 0;
        }
    }

    public static Scenarios scenarios(Property property) {
        BigDecimal prudent = property.estimateLow() != null ? property.estimateLow() : property.purchase();
        BigDecimal optimistic = property.estimateHigh() != null ? property.estimateHigh().max(prudent) : prudent;
        BigDecimal middle = prudent.add(optimistic).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new Scenarios(round(prudent.multiply(PESSIMISTIC_FACTOR)), round(prudent), round(middle), round(optimistic));
    }

    public static BigDecimal retained(Property property) {
        return property.selectedValue() != null ? property.selectedValue() : scenarios(property).prudent();
    }

    public static String retainedScenario(Property property) {
        return scenarios(property).nameOf(retained(property));
    }

    /** Valeur nette indicative = valeur − frais de vente et fiscalite − capital restant du. */
    public static BigDecimal net(BigDecimal value, BigDecimal saleFeesRate, BigDecimal debt) {
        return value.subtract(value.multiply(saleFeesRate)).subtract(debt).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal round(BigDecimal value) {
        return value.divide(STEP, 0, RoundingMode.HALF_UP).multiply(STEP);
    }
}
