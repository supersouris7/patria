// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Vente retenue pour le calcul du prix du marche d'un bien, affichee dans le detail des ventes.
 *
 * @param perSquareMeter prix au m², actualise comme dans le calcul de la mediane
 * @param distance       distance au bien en metres, {@code null} si seule la commune du bien est connue
 */
public record MarketSale(LocalDate date, String address, BigDecimal area, BigDecimal price, BigDecimal perSquareMeter, Integer distance,
                         double latitude, double longitude) {
}
