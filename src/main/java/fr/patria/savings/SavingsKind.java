// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.savings;

import java.math.BigDecimal;

/**
 * Types de placement. {@code ceiling} : plafond des versements (livrets reglementes), {@code null} sans plafond ;
 * {@code available} : argent disponible tout de suite (epargne de precaution) ; {@code stocks} : lignes de bourse.
 */
public enum SavingsKind {
    CURRENT("Compte courant", null, true, false),
    LIVRET_A("Livret A", new BigDecimal("22950"), true, false),
    LDDS("LDDS", new BigDecimal("12000"), true, false),
    LEP("LEP", new BigDecimal("10000"), true, false),
    LIVRET_JEUNE("Livret jeune", new BigDecimal("1600"), true, false),
    BOOKLET("Autre livret bancaire", null, true, false),
    CEL("CEL", new BigDecimal("15300"), true, false),
    PEL("PEL", new BigDecimal("61200"), false, false),
    LIFE_INSURANCE("Assurance-vie", null, false, false),
    RETIREMENT("Épargne retraite (PER)", null, false, false),
    PEA("PEA", new BigDecimal("150000"), false, true),
    SECURITIES("Compte-titres", null, false, true),
    OTHER("Autre placement", null, false, false);

    private final String label;
    private final BigDecimal ceiling;
    private final boolean available;
    private final boolean stocks;

    SavingsKind(String label, BigDecimal ceiling, boolean available, boolean stocks) {
        this.label = label;
        this.ceiling = ceiling;
        this.available = available;
        this.stocks = stocks;
    }

    public String label() {
        return label;
    }

    public BigDecimal ceiling() {
        return ceiling;
    }

    public boolean available() {
        return available;
    }

    public boolean stocks() {
        return stocks;
    }

    /** Livret reglemente : taux fixe par l'Etat (voir {@link RegulatedRates}). */
    public boolean regulated() {
        return this == LIVRET_A || this == LDDS || this == LEP || this == LIVRET_JEUNE || this == CEL || this == PEL;
    }

    public static SavingsKind parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new fr.patria.common.ValidationException("Type de placement invalide.");
        }
    }
}
