package fr.patria.rent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Loyers au jour pres a partir des periodes d'occupation : loyers percus (location) et loyers economises
 * (residence principale). Les jours sans periode sont vacants.
 */
public final class RentMath {
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    private RentMath() {
    }

    /** Loyers percus (locations) jusqu'a aujourd'hui. */
    public static BigDecimal receivedToDate(List<RentPeriod> periods, LocalDate today) {
        return toDate(periods, Occupancy.RENTAL, today);
    }

    /** Loyers economises en occupant le bien comme residence principale, jusqu'a aujourd'hui. */
    public static BigDecimal savedToDate(List<RentPeriod> periods, LocalDate today) {
        return toDate(periods, Occupancy.OWNER, today);
    }

    private static BigDecimal toDate(List<RentPeriod> periods, Occupancy kind, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (RentPeriod period : periods) {
            if (period.kind() != kind) continue;
            LocalDate end = period.end() == null || period.end().isAfter(today) ? today : period.end();
            if (end.isBefore(period.start())) continue;
            long days = ChronoUnit.DAYS.between(period.start(), end) + 1;
            total = total.add(period.monthly().multiply(BigDecimal.valueOf(12)).multiply(BigDecimal.valueOf(days)).divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP));
        }
        return total;
    }

    /** Loyer percu en cours aujourd'hui (0 si le bien n'est pas loue). */
    public static BigDecimal currentMonthly(List<RentPeriod> periods, LocalDate today) {
        return current(periods, today).stream().filter(period -> period.kind() == Occupancy.RENTAL)
                .map(RentPeriod::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Usage du bien aujourd'hui, ou {@code null} si aucune periode n'est en cours (vacant). */
    public static RentPeriod currentPeriod(List<RentPeriod> periods, LocalDate today) {
        List<RentPeriod> current = current(periods, today);
        return current.stream().filter(period -> period.kind() == Occupancy.OWNER).findFirst()
                .orElse(current.isEmpty() ? null : current.getFirst());
    }

    private static List<RentPeriod> current(List<RentPeriod> periods, LocalDate today) {
        return periods.stream().filter(period -> !period.start().isAfter(today) && (period.end() == null || !period.end().isBefore(today))).toList();
    }
}
