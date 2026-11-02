package fr.patria;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Calculs derives d'un echeancier. Toutes les pages passent par ici afin qu'un meme chiffre
 * (capital restant du, prochaine echeance, taux...) soit identique partout.
 */
final class LoanMath {
    static final String DRAWDOWN = "DEBLOCAGE";
    static final String INSTALLMENT = "ECHEANCE";
    static final String EARLY_REPAYMENT = "REMBOURSEMENT_ANTICIPE";

    private LoanMath() {
    }

    /** Une ligne est consideree comme passee (payee) le jour de son echeance. */
    static boolean isPast(ScheduleLine line, LocalDate today) {
        return !line.date().isAfter(today);
    }

    record Status(BigDecimal borrowed, BigDecimal repaid, BigDecimal remaining, BigDecimal currentRate,
                  ScheduleLine nextInstallment, LocalDate lastDate, long remainingMonths, int progressPercent) {
    }

    static Status status(Loan loan, List<ScheduleLine> unsorted, LocalDate today) {
        List<ScheduleLine> lines = sorted(unsorted);
        BigDecimal borrowed = borrowed(loan, lines);
        BigDecimal repaid = BigDecimal.ZERO;
        boolean hasDrawdowns = lines.stream().anyMatch(line -> line.type().equals(DRAWDOWN) && line.amount() != null);
        BigDecimal balance = hasDrawdowns ? BigDecimal.ZERO : borrowed;
        boolean started = false;
        BigDecimal currentRate = null;
        ScheduleLine next = null;
        for (ScheduleLine line : lines) {
            if (!isPast(line, today)) {
                if (next == null && line.type().equals(INSTALLMENT) && line.amount() != null) next = line;
                continue;
            }
            started = true;
            if (line.annualRate() != null) currentRate = line.annualRate();
            BigDecimal principal = principalRepaid(line);
            repaid = repaid.add(principal);
            if (line.type().equals(DRAWDOWN) && line.amount() != null) balance = balance.add(line.amount());
            if (line.remainingPrincipal() != null) balance = line.remainingPrincipal();
            else balance = balance.subtract(principal);
            balance = balance.max(BigDecimal.ZERO);
        }
        // Pret pas encore commence : on doit l'integralite du capital emprunte.
        BigDecimal remaining = started ? balance : borrowed;
        if (currentRate == null) currentRate = lines.stream().map(ScheduleLine::annualRate).filter(Objects::nonNull).findFirst().orElse(loan.annualRate());
        LocalDate last = lines.isEmpty() ? null : lines.getLast().date();
        long months = last == null ? 0 : Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(last)));
        return new Status(borrowed, repaid, remaining, currentRate, next, last, months, progress(borrowed, remaining));
    }

    /** Capital rembourse par une ligne : colonne dediee, sinon montant d'un remboursement anticipe. */
    static BigDecimal principalRepaid(ScheduleLine line) {
        if (line.principalRepaid() != null) return line.principalRepaid();
        if (line.type().equals(EARLY_REPAYMENT) && line.amount() != null) return line.amount();
        return BigDecimal.ZERO;
    }

    /** Capital emprunte : somme des deblocages, sinon capital avant la premiere ligne, sinon valeur enregistree. */
    static BigDecimal borrowed(Loan loan, List<ScheduleLine> lines) {
        BigDecimal drawdowns = lines.stream().filter(line -> line.type().equals(DRAWDOWN)).map(ScheduleLine::amount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (drawdowns.signum() > 0) return drawdowns;
        BigDecimal fromSchedule = initialFromSchedule(sorted(lines));
        if (fromSchedule.signum() > 0) return fromSchedule;
        return loan == null || loan.initialAmount() == null ? BigDecimal.ZERO : loan.initialAmount();
    }

    private static BigDecimal initialFromSchedule(List<ScheduleLine> lines) {
        for (ScheduleLine line : lines) {
            if (line.remainingPrincipal() != null) return line.remainingPrincipal().add(principalRepaid(line));
        }
        return BigDecimal.ZERO;
    }

    static int progress(BigDecimal borrowed, BigDecimal remaining) {
        if (borrowed.signum() <= 0) return 0;
        return borrowed.subtract(remaining).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(100))
                .divide(borrowed, 0, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100)).intValue();
    }

    /** Valeurs de reference stockees sur le pret, recalculees a chaque import d'echeancier. */
    static Loan derive(long id, long propertyId, String name, List<ScheduleLine> unsorted) {
        List<ScheduleLine> lines = sorted(unsorted);
        LocalDate start = lines.getFirst().date();
        LocalDate end = lines.getLast().date();
        BigDecimal initial = borrowed(null, lines);
        BigDecimal rate = lines.stream().map(ScheduleLine::annualRate).filter(Objects::nonNull).findFirst().orElse(BigDecimal.ZERO);
        BigDecimal payment = lines.stream().filter(line -> line.type().equals(INSTALLMENT)).map(ScheduleLine::amount)
                .filter(Objects::nonNull).findFirst().orElse(BigDecimal.ZERO);
        int duration = Math.max(1, (int) ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1);
        return new Loan(id, propertyId, "", name, initial, initial, start, rate, duration, start, start.getDayOfMonth(), payment, BigDecimal.ZERO);
    }

    record Month(YearMonth month, BigDecimal total, Map<String, BigDecimal> byLoan, boolean paid) {
    }

    /** Echeances regroupees par mois, tous credits confondus. Un mois est paye quand toutes ses echeances sont passees. */
    static List<Month> months(Collection<Loan> loans, Map<Long, List<ScheduleLine>> schedules, LocalDate today) {
        Map<YearMonth, Map<String, BigDecimal>> amounts = new TreeMap<>();
        Map<YearMonth, Boolean> paid = new TreeMap<>();
        for (Loan loan : loans) {
            for (ScheduleLine line : schedules.getOrDefault(loan.id(), List.of())) {
                if (!line.type().equals(INSTALLMENT) || line.amount() == null || line.amount().signum() == 0) continue;
                YearMonth month = YearMonth.from(line.date());
                amounts.computeIfAbsent(month, key -> new LinkedHashMap<>()).merge(loan.name(), line.amount(), BigDecimal::add);
                paid.merge(month, isPast(line, today), Boolean::logicalAnd);
            }
        }
        List<Month> result = new ArrayList<>();
        amounts.forEach((month, byLoan) -> result.add(new Month(month, byLoan.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), byLoan, paid.get(month))));
        return result;
    }

    /** Premier mois comportant une echeance non encore passee. */
    static Month nextMonth(Collection<Loan> loans, Map<Long, List<ScheduleLine>> schedules, LocalDate today) {
        return months(loans, schedules, today).stream().filter(month -> !month.paid()).findFirst().orElse(null);
    }

    private static List<ScheduleLine> sorted(List<ScheduleLine> lines) {
        return lines.stream().sorted(Comparator.comparing(ScheduleLine::date)).toList();
    }
}
