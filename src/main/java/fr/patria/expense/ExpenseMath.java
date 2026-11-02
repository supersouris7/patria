package fr.patria.expense;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Charges annee par annee depuis l'achat. Une annee saisie est reprise telle quelle ; une annee non saisie est
 * estimee a partir de l'annee saisie la plus proche (taxe fonciere revalorisee, autres charges constantes).
 */
public final class ExpenseMath {
    private ExpenseMath() {
    }

    /** Montant d'une charge pour une annee ; {@code estimated} si l'annee n'a pas ete saisie. */
    public record YearAmount(ExpenseKind kind, int year, BigDecimal amount, boolean estimated) {
    }

    /** Toutes les annees de l'achat a l'annee en cours, pour chaque type de charge saisi au moins une fois. */
    public static List<YearAmount> years(List<Expense> entered, LocalDate purchaseDate, LocalDate today) {
        Map<ExpenseKind, TreeMap<Integer, BigDecimal>> byKind = new EnumMap<>(ExpenseKind.class);
        for (Expense expense : entered) byKind.computeIfAbsent(expense.kind(), key -> new TreeMap<>()).put(expense.year(), expense.amount());
        List<YearAmount> result = new ArrayList<>();
        int first = Math.min(purchaseDate.getYear(), today.getYear());
        for (Map.Entry<ExpenseKind, TreeMap<Integer, BigDecimal>> kind : byKind.entrySet()) {
            for (int year = first; year <= today.getYear(); year++) {
                BigDecimal actual = kind.getValue().get(year);
                result.add(actual != null ? new YearAmount(kind.getKey(), year, actual, false)
                        : new YearAmount(kind.getKey(), year, estimate(kind.getKey(), kind.getValue(), year), true));
            }
        }
        result.sort(Comparator.comparing(YearAmount::year).reversed().thenComparing(YearAmount::kind));
        return result;
    }

    /** Total paye depuis l'achat, au prorata de l'annee d'achat et de l'annee en cours. */
    public static BigDecimal paidToDate(List<Expense> entered, LocalDate purchaseDate, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (YearAmount year : years(entered, purchaseDate, today)) total = total.add(year.amount().multiply(share(year.year(), purchaseDate, today)));
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Total annuel de l'annee en cours (saisi ou estime). */
    public static BigDecimal currentYear(List<Expense> entered, LocalDate purchaseDate, LocalDate today) {
        return years(entered, purchaseDate, today).stream().filter(year -> year.year() == today.getYear())
                .map(YearAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Montant d'une charge pour une annee quelconque (saisi ou estime) ; {@code null} si ce type n'a jamais ete saisi. */
    public static BigDecimal amountFor(List<Expense> entered, ExpenseKind kind, int year) {
        TreeMap<Integer, BigDecimal> byYear = new TreeMap<>();
        for (Expense expense : entered) if (expense.kind() == kind) byYear.put(expense.year(), expense.amount());
        if (byYear.isEmpty()) return null;
        return byYear.containsKey(year) ? byYear.get(year) : estimate(kind, byYear, year);
    }

    static BigDecimal estimate(ExpenseKind kind, TreeMap<Integer, BigDecimal> entered, int year) {
        Integer before = entered.floorKey(year);
        Integer after = entered.ceilingKey(year);
        int anchor = before == null ? after : after == null ? before : (year - before <= after - year ? before : after);
        BigDecimal amount = entered.get(anchor);
        if (kind != ExpenseKind.PROPERTY_TAX) return amount;
        BigDecimal factor = BigDecimal.ONE;
        for (int y = Math.min(anchor, year) + 1; y <= Math.max(anchor, year); y++) factor = factor.multiply(PropertyTaxRevaluation.of(y));
        BigDecimal estimated = year > anchor ? amount.multiply(factor) : amount.divide(factor, MathContext.DECIMAL64);
        return estimated.setScale(0, RoundingMode.HALF_UP);
    }

    /** Part de l'annee pendant laquelle le bien etait detenu, jusqu'a aujourd'hui. */
    static BigDecimal share(int year, LocalDate purchaseDate, LocalDate today) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        LocalDate from = purchaseDate.isAfter(start) ? purchaseDate : start;
        LocalDate to = today.isBefore(end) ? today : end;
        if (to.isBefore(from)) return BigDecimal.ZERO;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(start.lengthOfYear()), 6, RoundingMode.HALF_UP);
    }
}
