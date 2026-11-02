package fr.patria.rent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Loyers percus, estimes au jour pres a partir des periodes de location (les mois sans periode sont vacants). */
public final class RentMath {
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    private RentMath() {
    }

    /** Loyers percus depuis le debut de chaque periode jusqu'a aujourd'hui (ou la fin de la periode). */
    public static BigDecimal receivedToDate(List<RentPeriod> periods, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        for (RentPeriod period : periods) {
            LocalDate end = period.end() == null || period.end().isAfter(today) ? today : period.end();
            if (end.isBefore(period.start())) continue;
            long days = ChronoUnit.DAYS.between(period.start(), end) + 1;
            total = total.add(period.monthly().multiply(BigDecimal.valueOf(12)).multiply(BigDecimal.valueOf(days)).divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP));
        }
        return total;
    }

    /** Loyer mensuel en cours aujourd'hui (0 si le bien est vacant). */
    public static BigDecimal currentMonthly(List<RentPeriod> periods, LocalDate today) {
        return periods.stream().filter(period -> !period.start().isAfter(today) && (period.end() == null || !period.end().isBefore(today)))
                .map(RentPeriod::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
