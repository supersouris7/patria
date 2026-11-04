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
 * Les estimations peuvent etre actualisees par un coefficient (evolution des prix du departement depuis la date
 * d'estimation, calcule par le module « marche »). La valeur retenue est celle choisie avec le curseur, sinon le
 * scenario prudent actualise.
 * La meme formule de valeur nette est reprise dans {@code app.js} pour l'affichage en direct.
 */
public final class Valuation {
    public static final BigDecimal DEFAULT_SALE_FEES = new BigDecimal("0.08");
    private static final BigDecimal PESSIMISTIC_FACTOR = new BigDecimal("0.95");
    /** Arrondi des scenarios : des valeurs rondes, lisibles. */
    private static final BigDecimal STEP = new BigDecimal("10000");
    /** Pas du curseur de la valeur retenue : 1 000 €, pour un reglage fin. */
    static final BigDecimal SLIDER_STEP = new BigDecimal("1000");
    private static final BigDecimal SLIDER_MARGIN = new BigDecimal("50000");
    /** Plafond de l'actualisation (identique a celui de l'indice DVF). */
    private static final BigDecimal MAX_REVALUATION = new BigDecimal("1.33");

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

        /**
         * Ramene une valeur enregistree par le curseur sur sa graduation (pas de 1 000 €). Les bornes sont larges
         * car le curseur affiche les scenarios actualises, inconnus ici.
         */
        public BigDecimal clamp(BigDecimal value) {
            BigDecimal step = value.divide(SLIDER_STEP, 0, RoundingMode.HALF_UP).multiply(SLIDER_STEP);
            return step.max(BigDecimal.ZERO).min(round(optimistic.multiply(MAX_REVALUATION)).add(SLIDER_MARGIN));
        }

        boolean sameAs(Scenarios other) {
            return pessimistic.compareTo(other.pessimistic) == 0 && prudent.compareTo(other.prudent) == 0
                    && middle.compareTo(other.middle) == 0 && optimistic.compareTo(other.optimistic) == 0;
        }
    }

    /** Scenarios sans actualisation (estimations telles que saisies). */
    public static Scenarios scenarios(Property property) {
        return scenarios(property, BigDecimal.ONE);
    }

    /** Scenarios actualises : estimations × coefficient d'evolution des prix depuis la date d'estimation. */
    public static Scenarios scenarios(Property property, BigDecimal revaluation) {
        BigDecimal prudent = base(property).multiply(revaluation);
        BigDecimal optimistic = (property.estimateHigh() != null ? property.estimateHigh().max(base(property)) : base(property)).multiply(revaluation);
        BigDecimal middle = prudent.add(optimistic).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new Scenarios(round(prudent.multiply(PESSIMISTIC_FACTOR)), round(prudent), round(middle), round(optimistic));
    }

    /** Estimation prudente telle que saisie, ou a defaut le cout d'acquisition. */
    public static BigDecimal base(Property property) {
        return property.estimateLow() != null ? property.estimateLow() : property.purchase();
    }

    public static BigDecimal retained(Property property) {
        return retained(property, BigDecimal.ONE);
    }

    /** Valeur choisie avec le curseur, sinon le scenario prudent actualise. */
    public static BigDecimal retained(Property property, BigDecimal revaluation) {
        return property.selectedValue() != null ? property.selectedValue() : scenarios(property, revaluation).prudent();
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
