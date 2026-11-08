package fr.patria.savings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Taux des livrets reglementes. Aucune API publique ne les donne produit par produit (la Banque de France ne publie
 * qu'un taux moyen de tous les livrets) : ils sont tenus ici, dates, d'apres service-public.gouv.fr (verifie le
 * 08/11/2026 : arretes du 28 et 29 juillet 2026, du 1er aout 2026 au 31 janvier 2027). A mettre a jour a chaque
 * revision (1er fevrier et 1er aout) ; l'audit du bilan signale un changement recent.
 */
public final class RegulatedRates {
    /** Un taux et la date depuis laquelle il s'applique. */
    public record Rate(BigDecimal percent, LocalDate since) {
    }

    /** Historique recent, du plus ancien au plus recent. PEL : taux des plans ouverts a cette date. */
    private static final List<Entry> TABLE = List.of(
            // Livret A, LDDS, CEL : seul le taux en vigueur depuis le 1er aout 2026 est verifie (pas le precedent).
            new Entry(SavingsKind.LIVRET_A, "1.7", "2026-08-01"),
            new Entry(SavingsKind.LDDS, "1.7", "2026-08-01"),
            new Entry(SavingsKind.LEP, "2.7", "2025-08-01"),
            new Entry(SavingsKind.LEP, "2.5", "2026-02-01"),
            new Entry(SavingsKind.LIVRET_JEUNE, "1.7", "2026-08-01"),
            new Entry(SavingsKind.CEL, "1.25", "2026-08-01"),
            new Entry(SavingsKind.PEL, "1.75", "2025-01-01"),
            new Entry(SavingsKind.PEL, "2.0", "2026-01-01"));

    /** Date de verification de la table, affichee avec les taux. */
    public static final LocalDate CHECKED = LocalDate.of(2026, 11, 8);
    public static final String SOURCE = "service-public.gouv.fr";

    /** Plafond de revenu fiscal de reference pour ouvrir un LEP en 2026 (metropole, 1 part ; + 6 149 € par demi-part). */
    public static final BigDecimal LEP_INCOME_ONE_PART = new BigDecimal("23028");
    public static final BigDecimal LEP_INCOME_HALF_PART = new BigDecimal("6149");

    private record Entry(SavingsKind kind, String percent, String since) {
    }

    private RegulatedRates() {
    }

    /** Taux en vigueur a {@code date}, vide pour un placement non reglemente (ou un livret jeune, fixe par la banque, au moins le Livret A). */
    public static Optional<Rate> at(SavingsKind kind, LocalDate date) {
        return TABLE.stream().filter(entry -> entry.kind() == kind && !LocalDate.parse(entry.since()).isAfter(date))
                .reduce((first, second) -> second).map(entry -> new Rate(new BigDecimal(entry.percent()), LocalDate.parse(entry.since())));
    }

    /** Taux precedent, pour signaler une baisse ou une hausse recente. */
    public static Optional<Rate> before(SavingsKind kind, LocalDate date) {
        Optional<Rate> current = at(kind, date);
        return current.flatMap(rate -> at(kind, rate.since().minusDays(1)));
    }
}
