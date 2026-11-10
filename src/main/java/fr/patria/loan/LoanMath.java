// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Calculs derives d'un echeancier, a une date donnee. Toutes les pages passent par ici afin qu'un meme chiffre
 * (capital restant du, prochaine echeance, taux...) soit identique partout.
 *
 * <p>Convention : une echeance est consideree comme payee le jour meme de sa date.
 */
public final class LoanMath {
    private LoanMath() {
    }

    /**
     * Situation d'un pret a une date.
     *
     * @param borrowed        capital emprunte (somme des deblocages, sinon capital avant la premiere echeance)
     * @param repaid          capital rembourse par les echeances passees
     * @param remaining       capital restant du ; avant la premiere ligne, tout le capital emprunte
     * @param currentRate     taux de la derniere ligne passee (sinon le premier taux connu)
     * @param nextInstallment prochaine echeance a payer, {@code null} s'il n'y en a plus
     * @param creditCost      cout du credit deja paye : interets et frais (assurance, dossier...) des lignes passees
     * @param interestPaid    interets seuls des lignes passees (hors assurance et frais)
     * @param interestToPay   interets seuls des lignes a venir
     */
    public record Status(BigDecimal borrowed, BigDecimal repaid, BigDecimal remaining, BigDecimal currentRate,
                         ScheduleLine nextInstallment, LocalDate lastDate, long remainingMonths, int progressPercent,
                         BigDecimal creditCost, BigDecimal interestPaid, BigDecimal interestToPay) {
        /** Interets sur toute la duree du pret. */
        public BigDecimal interestTotal() {
            return interestPaid.add(interestToPay);
        }

        /** Part des interets deja payee, de 0 a 100. */
        public int interestPaidPercent() {
            BigDecimal total = interestTotal();
            if (total.signum() == 0) return 0;
            return interestPaid.multiply(BigDecimal.valueOf(100)).divide(total, 0, java.math.RoundingMode.HALF_UP).intValue();
        }
    }

    public static boolean isPast(ScheduleLine line, LocalDate today) {
        return !line.date().isAfter(today);
    }

    public static Status status(List<ScheduleLine> unsorted, LocalDate today) {
        List<ScheduleLine> lines = sorted(unsorted);
        BigDecimal borrowed = borrowed(lines);
        boolean hasDrawdowns = lines.stream().anyMatch(line -> line.type() == LineType.DRAWDOWN && line.amount() != null);
        BigDecimal balance = hasDrawdowns ? BigDecimal.ZERO : borrowed;
        BigDecimal repaid = BigDecimal.ZERO;
        BigDecimal creditCost = BigDecimal.ZERO;
        BigDecimal interestPaid = BigDecimal.ZERO;
        BigDecimal interestToPay = BigDecimal.ZERO;
        BigDecimal currentRate = null;
        ScheduleLine next = null;
        boolean started = false;
        for (ScheduleLine line : lines) {
            if (!isPast(line, today)) {
                interestToPay = interestToPay.add(interest(line));
                if (next == null && line.type() == LineType.INSTALLMENT && line.amount() != null) next = line;
                continue;
            }
            started = true;
            if (line.annualRate() != null) currentRate = line.annualRate();
            BigDecimal principal = principalRepaid(line);
            repaid = repaid.add(principal);
            creditCost = creditCost.add(cost(line));
            interestPaid = interestPaid.add(interest(line));
            if (line.type() == LineType.DRAWDOWN && line.amount() != null) balance = balance.add(line.amount());
            balance = line.remainingPrincipal() != null ? line.remainingPrincipal() : balance.subtract(principal);
            balance = balance.max(BigDecimal.ZERO);
        }
        BigDecimal remaining = started ? balance : borrowed;
        if (currentRate == null) currentRate = lines.stream().map(ScheduleLine::annualRate).filter(Objects::nonNull).findFirst().orElse(null);
        LocalDate last = lines.isEmpty() ? null : lines.getLast().date();
        long months = last == null ? 0 : Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(last)));
        return new Status(borrowed, repaid, remaining, currentRate, next, last, months, progress(borrowed, remaining), creditCost,
                interestPaid, interestToPay);
    }

    /**
     * Interets d'une ligne : colonne dediee. Si l'echeancier ne detaille ni interets ni frais, une echeance vaut
     * montant − capital rembourse (assurance eventuelle comprise, faute de mieux).
     */
    static BigDecimal interest(ScheduleLine line) {
        if (line.interest() != null) return line.interest();
        if (line.fees() != null) return BigDecimal.ZERO;
        return cost(line);
    }

    /**
     * Cout d'une ligne : interets + frais. Si l'echeancier ne detaille pas les interets, une echeance vaut
     * montant − capital rembourse (ce qui inclut l'assurance).
     */
    static BigDecimal cost(ScheduleLine line) {
        if (line.interest() != null || line.fees() != null) {
            return (line.interest() == null ? BigDecimal.ZERO : line.interest()).add(line.fees() == null ? BigDecimal.ZERO : line.fees());
        }
        if (line.type() == LineType.INSTALLMENT && line.amount() != null && line.principalRepaid() != null) {
            return line.amount().subtract(line.principalRepaid()).max(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    /** Capital rembourse par une ligne : colonne dediee, sinon montant d'un remboursement anticipe. */
    static BigDecimal principalRepaid(ScheduleLine line) {
        if (line.principalRepaid() != null) return line.principalRepaid();
        if (line.type() == LineType.EARLY_REPAYMENT && line.amount() != null) return line.amount();
        return BigDecimal.ZERO;
    }

    /** Capital emprunte : somme des deblocages, sinon capital restant + capital rembourse de la premiere ligne qui le precise. */
    static BigDecimal borrowed(List<ScheduleLine> sortedLines) {
        BigDecimal drawdowns = sortedLines.stream().filter(line -> line.type() == LineType.DRAWDOWN).map(ScheduleLine::amount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (drawdowns.signum() > 0) return drawdowns;
        return sortedLines.stream().filter(line -> line.remainingPrincipal() != null).findFirst()
                .map(line -> line.remainingPrincipal().add(principalRepaid(line))).orElse(BigDecimal.ZERO);
    }

    static int progress(BigDecimal borrowed, BigDecimal remaining) {
        if (borrowed.signum() <= 0) return 0;
        return borrowed.subtract(remaining).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(100))
                .divide(borrowed, 0, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100)).intValue();
    }

    /** Total des echeances d'un mois, tous prets confondus. Le mois est paye quand toutes ses echeances sont passees. */
    public record Month(YearMonth month, BigDecimal total, Map<String, BigDecimal> byLoan, boolean paid) {
    }

    public static List<Month> months(Collection<Loan> loans, Map<Long, List<ScheduleLine>> schedules, LocalDate today) {
        Map<YearMonth, Map<String, BigDecimal>> amounts = new TreeMap<>();
        Map<YearMonth, Boolean> paid = new TreeMap<>();
        for (Loan loan : loans) {
            for (ScheduleLine line : schedules.getOrDefault(loan.id(), List.of())) {
                if (line.type() != LineType.INSTALLMENT || line.amount() == null || line.amount().signum() == 0) continue;
                YearMonth month = YearMonth.from(line.date());
                amounts.computeIfAbsent(month, key -> new LinkedHashMap<>()).merge(loan.name(), line.amount(), BigDecimal::add);
                paid.merge(month, isPast(line, today), Boolean::logicalAnd);
            }
        }
        return amounts.entrySet().stream().map(entry -> new Month(entry.getKey(),
                entry.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), entry.getValue(), paid.get(entry.getKey()))).toList();
    }

    /** Premier mois comportant une echeance non encore passee, ou {@code null}. */
    public static Month nextMonth(Collection<Loan> loans, Map<Long, List<ScheduleLine>> schedules, LocalDate today) {
        return months(loans, schedules, today).stream().filter(month -> !month.paid()).findFirst().orElse(null);
    }

    private static List<ScheduleLine> sorted(List<ScheduleLine> lines) {
        return lines.stream().sorted(Comparator.comparing(ScheduleLine::date)).toList();
    }
}
