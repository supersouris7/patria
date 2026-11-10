// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.expense;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * Coefficients legaux de revalorisation forfaitaire des valeurs locatives (base de la taxe fonciere).
 * Depuis 2018, ils suivent l'inflation (IPCH de novembre a novembre).
 *
 * <p>A completer chaque annee (loi de finances / BOFiP). Une annee absente reprend le coefficient connu le plus proche.
 */
final class PropertyTaxRevaluation {
    private static final TreeMap<Integer, BigDecimal> COEFFICIENTS = new TreeMap<>(Map.ofEntries(
            Map.entry(2014, new BigDecimal("1.009")),
            Map.entry(2015, new BigDecimal("1.009")),
            Map.entry(2016, new BigDecimal("1.010")),
            Map.entry(2017, new BigDecimal("1.004")),
            Map.entry(2018, new BigDecimal("1.012")),
            Map.entry(2019, new BigDecimal("1.022")),
            Map.entry(2020, new BigDecimal("1.012")),
            Map.entry(2021, new BigDecimal("1.002")),
            Map.entry(2022, new BigDecimal("1.034")),
            Map.entry(2023, new BigDecimal("1.071")),
            Map.entry(2024, new BigDecimal("1.039")),
            Map.entry(2025, new BigDecimal("1.017"))));

    private PropertyTaxRevaluation() {
    }

    /** Coefficient applique pour passer de l'annee {@code year − 1} a {@code year}. */
    static BigDecimal of(int year) {
        if (COEFFICIENTS.containsKey(year)) return COEFFICIENTS.get(year);
        return year > COEFFICIENTS.lastKey() ? COEFFICIENTS.lastEntry().getValue() : COEFFICIENTS.firstEntry().getValue();
    }
}
