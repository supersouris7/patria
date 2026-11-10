// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Taux moyen des nouveaux credits immobiliers en France, taux fixe sur plus de 10 ans (statistiques MIR de la BCE,
 * transmises par la Banque de France) : taux nominal annuel en %, hors assurance et frais.
 *
 * @param month mois de la statistique (publiee avec environ deux mois de decalage)
 */
public record HousingRate(BigDecimal rate, YearMonth month) {
}
