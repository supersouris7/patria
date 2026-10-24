package fr.patria;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Loan(
        long id,
        long propertyId,
        String propertyName,
        String name,
        BigDecimal initialAmount,
        BigDecimal referenceBalance,
        LocalDate referenceDate,
        BigDecimal annualRate,
        int durationMonths,
        LocalDate startDate,
        int dueDay,
        BigDecimal monthlyPayment,
        BigDecimal monthlyInsurance) {
}
