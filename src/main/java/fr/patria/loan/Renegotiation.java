package fr.patria.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Comparaison d'un pret avec le taux moyen actuel des nouveaux credits, et repere de renegociation. Fonctions pures.
 *
 * <p>Calcul : capital restant du et duree restante inchanges, mensualite hors assurance recalculee au taux actuel
 * (pret amortissable). Economie brute = difference des mensualites × duree restante. Penalites de remboursement
 * anticipe au plafond legal : 6 mois d'interets au taux du pret, sans depasser 3 % du capital restant du. Les frais
 * de dossier, de garantie et l'assurance ne sont pas comptes.
 *
 * @param gap          ecart en points : taux du pret − taux du marche (positif : le pret est plus cher)
 * @param shortRemaining duree restante de moins de 10 ans : la reference (taux fixe plus de 10 ans) est moins comparable
 */
public record Renegotiation(BigDecimal ownRate, BigDecimal marketRate, YearMonth month, BigDecimal gap, BigDecimal remaining, long remainingMonths,
                            BigDecimal currentPayment, BigDecimal newPayment, BigDecimal grossSaving, BigDecimal penalty, BigDecimal netSaving,
                            boolean shortRemaining) {
    /** Taux de reference : taux moyen du marche et mois de la statistique. */
    public record Reference(BigDecimal rate, YearMonth month) {
    }

    /** Lecture du resultat. */
    public enum Verdict {
        /** Ecart d'au moins 0,7 point et economie nette : demander une simulation a sa banque. */
        WORTH,
        /** Economie nette, mais ecart faible : a surveiller. */
        SMALL,
        /** Pas d'economie : le pret est au taux du marche ou en dessous. */
        NONE
    }

    /** Ecart a partir duquel une renegociation est souvent jugee interessante. */
    static final BigDecimal THRESHOLD = new BigDecimal("0.7");
    private static final MathContext PRECISION = new MathContext(20);

    public Verdict verdict() {
        if (netSaving.signum() <= 0) return Verdict.NONE;
        return gap.compareTo(THRESHOLD) >= 0 ? Verdict.WORTH : Verdict.SMALL;
    }

    /** Vide si le pret est termine ou sans taux connu. */
    public static Optional<Renegotiation> of(LoanMath.Status status, Reference reference) {
        if (reference == null || status.currentRate() == null || status.remaining() == null || status.remaining().signum() <= 0
                || status.remainingMonths() <= 0) return Optional.empty();
        BigDecimal remaining = status.remaining();
        long months = status.remainingMonths();
        BigDecimal current = payment(remaining, status.currentRate(), months);
        BigDecimal renewed = payment(remaining, reference.rate(), months);
        BigDecimal gross = current.subtract(renewed).multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sixMonths = remaining.multiply(status.currentRate()).divide(BigDecimal.valueOf(200), 2, RoundingMode.HALF_UP);
        BigDecimal penalty = sixMonths.min(remaining.multiply(new BigDecimal("0.03")).setScale(2, RoundingMode.HALF_UP));
        BigDecimal net = gross.signum() <= 0 ? gross : gross.subtract(penalty);
        return Optional.of(new Renegotiation(status.currentRate(), reference.rate(), reference.month(), status.currentRate().subtract(reference.rate()),
                remaining, months, current, renewed, gross, penalty, net, months < 120));
    }

    /** Mensualite d'un pret amortissable : C × t / (1 − (1 + t)^−n), t taux mensuel. */
    static BigDecimal payment(BigDecimal principal, BigDecimal annualRatePercent, long months) {
        if (annualRatePercent.signum() == 0) return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal monthly = annualRatePercent.divide(BigDecimal.valueOf(1200), PRECISION);
        BigDecimal factor = BigDecimal.ONE.add(monthly).pow((int) months, PRECISION);
        BigDecimal payment = principal.multiply(monthly).multiply(factor).divide(factor.subtract(BigDecimal.ONE), PRECISION);
        return payment.setScale(2, RoundingMode.HALF_UP);
    }
}
