package fr.patria.health;

import static fr.patria.ui.Format.euroWhole;

import fr.patria.health.Check.Domain;
import fr.patria.health.Check.Level;
import fr.patria.savings.RegulatedRates;
import fr.patria.savings.Savings;
import fr.patria.savings.SavingsController;
import fr.patria.savings.SavingsKind;
import fr.patria.savings.SavingsRepository;
import fr.patria.ui.Format;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Audit automatique de l'epargne pour le bilan, en fonctions pures : precaution, argent qui dort, plafonds, LEP,
 * livrets sous l'inflation, bourse (concentration, plus-values, PEA de moins de 5 ans), changements de taux recents,
 * soldes anciens, pouvoir d'achat. Recalcule a chaque ouverture du bilan, avec les taux et l'inflation du moment.
 */
final class HealthSavings {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String LINK = "/epargne";

    private HealthSavings() {
    }

    static List<Check> audit(SavingsController.Snapshot snapshot, LocalDate today) {
        List<Check> checks = new ArrayList<>();
        Savings.Overview overview = snapshot.overview();
        Savings.Plan plan = snapshot.plan();
        purchasingPower(snapshot).ifPresent(checks::add);
        if (overview.isEmpty()) {
            checks.add(Check.of("savings-empty", Domain.SAVINGS, Level.INFO, "Épargne non renseignée",
                    "Saisissez vos livrets, assurances-vie et PEA : Patria calculera votre épargne de précaution, un plan pour la compléter et un audit de vos placements.")
                    .link(LINK, "Saisir mon épargne"));
            return checks;
        }
        BigDecimal spending = plan.spending();

        // 1. Precaution reelle
        if (spending.signum() > 0) {
            BigDecimal months = plan.coverMonths();
            Level level = months.compareTo(BigDecimal.ONE) < 0 ? Level.BAD : months.compareTo(BigDecimal.valueOf(3)) < 0 ? Level.WARN : Level.GOOD;
            String detail = level == Level.GOOD ? euroWhole(plan.available()) + " disponibles tout de suite : de quoi faire face à un imprévu ou une baisse de revenus."
                    : euroWhole(plan.available()) + " disponibles, il manque " + euroWhole(plan.missing()) + " pour l'objectif de " + plan.months() + " mois"
                    + (plan.monthly().signum() > 0 ? ". Versement conseillé : " + euroWhole(plan.monthly()) + " par mois." : ".");
            checks.add(Check.of("savings-cushion", Domain.SAVINGS, level, "Épargne de précaution : " + Format.number(months, 1) + " mois de dépenses", detail).link(LINK + "#plan", "Voir le plan"));
        }

        // 2. Argent qui dort sur les comptes courants
        BigDecimal current = sum(overview, SavingsKind.CURRENT);
        BigDecimal rateA = RegulatedRates.at(SavingsKind.LIVRET_A, today).map(RegulatedRates.Rate::percent).orElse(null);
        if (spending.signum() > 0 && current.compareTo(spending.multiply(BigDecimal.valueOf(2))) > 0) {
            BigDecimal sleeping = current.subtract(spending);
            checks.add(Check.of("savings-sleeping", Domain.SAVINGS, Level.WARN, euroWhole(sleeping) + " dorment sur les comptes courants",
                    "Au-delà d'un mois de dépenses, l'argent d'un compte courant ne rapporte rien." + (rateA == null ? "" : " Sur un Livret A à " + Format.percent(rateA)
                            + ", cela ferait environ " + euroWhole(interest(sleeping, rateA)) + " d'intérêts par an, toujours disponibles.")).link(LINK, "Voir mes comptes"));
        }

        // 3. Plafonds atteints
        for (Savings.Line line : overview.lines()) {
            SavingsRepository.Account account = line.account();
            if (account.kind().regulated() && account.kind().ceiling() != null && account.balance().compareTo(account.kind().ceiling()) >= 0) {
                checks.add(Check.of("savings-ceiling-" + account.id(), Domain.SAVINGS, Level.INFO, account.name() + " (" + line.owner() + ") : plafond atteint",
                        "Les intérêts continuent de s'ajouter, mais plus aucun versement n'est possible au-delà de " + euroWhole(account.kind().ceiling())
                                + ". Les versements suivants iront sur un autre livret (LDDS, LEP) ou sur le long terme.").link(LINK, "Voir le plan"));
            }
        }

        // 4. LEP
        BigDecimal rateLep = RegulatedRates.at(SavingsKind.LEP, today).map(RegulatedRates.Rate::percent).orElse(null);
        boolean hasLep = overview.lines().stream().anyMatch(line -> line.account().kind() == SavingsKind.LEP);
        boolean regulated = overview.lines().stream().anyMatch(line -> line.account().kind() == SavingsKind.LIVRET_A || line.account().kind() == SavingsKind.LDDS);
        if (!hasLep && rateLep != null && rateA != null) {
            if ("oui".equals(snapshot.lep())) {
                BigDecimal gain = interest(SavingsKind.LEP.ceiling(), rateLep.subtract(rateA));
                checks.add(Check.of("savings-lep", Domain.SAVINGS, Level.WARN, "LEP à ouvrir : " + Format.percent(rateLep) + " au lieu de " + Format.percent(rateA),
                        "Vous êtes éligible au LEP, le mieux rémunéré des livrets. Y placer " + euroWhole(SavingsKind.LEP.ceiling()) + " rapporterait environ "
                                + euroWhole(gain) + " de plus par an qu'un Livret A. À ouvrir dans votre banque avec votre avis d'impôt.").link(LINK + "#plan", "Voir le plan"));
            } else if (snapshot.lep().isEmpty() && regulated) {
                checks.add(Check.of("savings-lep-check", Domain.SAVINGS, Level.INFO, "Êtes-vous éligible au LEP (" + Format.percent(rateLep) + ") ?",
                        "Le LEP rapporte plus que le Livret A (" + Format.percent(rateA) + "). Il est ouvert si votre revenu fiscal de référence ne dépasse pas "
                                + euroWhole(RegulatedRates.LEP_INCOME_ONE_PART) + " pour 1 part (+ " + euroWhole(RegulatedRates.LEP_INCOME_HALF_PART)
                                + " par demi-part). Indiquez-le dans le plan d'épargne.").link(LINK + "#plan", "Répondre"));
            }
        }

        // 5. Livrets sous l'inflation
        snapshot.inflation().ifPresent(inflation -> {
            BigDecimal below = BigDecimal.ZERO;
            BigDecimal loss = BigDecimal.ZERO;
            for (Savings.Line line : overview.lines()) {
                if (!line.account().kind().available() || line.rate() == null || line.account().balance().signum() <= 0) continue;
                if (line.rate().compareTo(inflation.percent()) < 0) {
                    below = below.add(line.account().balance());
                    loss = loss.add(interest(line.account().balance(), inflation.percent().subtract(line.rate())));
                }
            }
            if (loss.compareTo(BigDecimal.TEN) >= 0) {
                checks.add(Check.of("savings-inflation", Domain.SAVINGS, Level.INFO, "Livrets sous l'inflation (" + Format.signedPercent(inflation.percent()) + ")",
                        euroWhole(below) + " rapportent moins que la hausse des prix : environ " + euroWhole(loss) + " de pouvoir d'achat perdu par an. C'est le prix de la "
                                + "disponibilité pour l'épargne de précaution ; au-delà de l'objectif, le long terme (assurance-vie, PEA) vise mieux, avec un risque.")
                        .link(LINK, "Voir mes livrets"));
            }
        });

        // 6. Bourse
        List<Savings.PositionLine> positions = overview.positions();
        if (!positions.isEmpty() && overview.stocks().signum() > 0) {
            Savings.PositionLine biggest = positions.stream().max(java.util.Comparator.comparing(Savings.PositionLine::value)).orElseThrow();
            BigDecimal share = biggest.value().multiply(HUNDRED).divide(overview.stocks(), 0, RoundingMode.HALF_UP);
            if (share.compareTo(Savings.CONCENTRATION) > 0 && !looksDiversified(biggest)) {
                checks.add(Check.of("savings-concentration", Domain.SAVINGS, Level.WARN, biggest.position().label() + " : " + share + " % de votre bourse",
                        "Une seule valeur pèse lourd : si elle chute, tout le portefeuille suit. Un fonds diversifié (ETF monde, par exemple) répartit le risque.")
                        .link(LINK, "Voir mes lignes"));
            }
            BigDecimal gain = overview.stockGain();
            checks.add(Check.of("savings-stocks", Domain.SAVINGS, gain.signum() >= 0 ? Level.GOOD : Level.INFO,
                    "Bourse : " + euroWhole(overview.stocks()) + " (" + Format.signedEuroWhole(gain) + ")",
                    (gain.signum() >= 0 ? "Plus-value latente" : "Moins-value latente") + " par rapport au prix de revient saisi. Rien n'est gagné ni perdu tant que rien n'est vendu."
                            + (positions.stream().anyMatch(position -> !position.priceSource().equals("cours du jour") && !position.priceSource().equals("saisi"))
                            ? " Certaines lignes n'ont pas de cours du jour : elles sont comptées à leur prix de revient." : "")).link(LINK, "Voir mes lignes"));
        }
        for (Savings.Line line : overview.lines()) {
            SavingsRepository.Account account = line.account();
            if (account.kind() == SavingsKind.PEA && account.openedOn() != null && account.openedOn().plusYears(5).isAfter(today)) {
                checks.add(Check.of("savings-pea-" + account.id(), Domain.SAVINGS, Level.INFO, account.name() + " : 5 ans le " + Format.date(account.openedOn().plusYears(5)),
                        "Avant 5 ans, un retrait clôture en principe le PEA et les gains sont imposés au prélèvement forfaitaire. Après 5 ans, seuls les prélèvements "
                                + "sociaux s'appliquent. Gardez cet argent pour le long terme.").link(LINK, "Voir mes placements"));
            }
        }

        // 7. Audit periodique : taux changes recemment, soldes anciens
        for (SavingsKind kind : List.of(SavingsKind.LIVRET_A, SavingsKind.LDDS, SavingsKind.LEP, SavingsKind.CEL)) {
            if (overview.lines().stream().noneMatch(line -> line.account().kind() == kind)) continue;
            Optional<RegulatedRates.Rate> now = RegulatedRates.at(kind, today);
            Optional<RegulatedRates.Rate> before = RegulatedRates.before(kind, today);
            if (now.isPresent() && before.isPresent() && now.get().since().plusDays(90).isAfter(today)) {
                boolean down = now.get().percent().compareTo(before.get().percent()) < 0;
                checks.add(Check.of("savings-rate-" + kind.name() + "-" + now.get().since(), Domain.SAVINGS, down ? Level.WARN : Level.GOOD,
                        kind.label() + " : " + Format.percent(before.get().percent()) + " → " + Format.percent(now.get().percent()),
                        "Nouveau taux depuis le " + Format.date(now.get().since()) + "." + (down ? " Vos intérêts baissent : le plan d'épargne en tient compte." : ""))
                        .link(LINK, "Voir mes livrets"));
            }
        }
        long stale = overview.lines().stream().filter(line -> line.account().kind() != SavingsKind.CURRENT && line.account().balanceDate().plusMonths(6).isBefore(today)).count();
        if (stale > 0) {
            checks.add(Check.of("savings-stale", Domain.SAVINGS, Level.INFO, Format.plural(stale, "solde à mettre à jour", "soldes à mettre à jour"),
                    "Ces soldes ont plus de 6 mois : mettez-les à jour pour que l'audit reste juste.").link(LINK, "Mettre à jour"));
        }
        return checks;
    }

    /** Evolution des revenus sur un an face a l'inflation. */
    static Optional<Check> purchasingPower(SavingsController.Snapshot snapshot) {
        return snapshot.power().map(power -> Check.of("purchasing-power", Domain.SAVINGS, power.power().signum() >= 0 ? Level.GOOD : Level.WARN,
                "Pouvoir d'achat : " + Format.signedPercent(power.power()) + " en un an",
                "Revenus : " + euroWhole(power.yearAgo()) + " → " + euroWhole(power.now()) + " par mois (" + Format.signedPercent(power.income()) + "), prix : "
                        + Format.signedPercent(power.inflation()) + " (INSEE)." + (power.power().signum() < 0
                        ? " Vos revenus progressent moins vite que les prix : un point à aborder lors d'une revue de salaire, ou dans le budget." : ""))
                .link(LINK, "Voir l'épargne"));
    }

    private static boolean looksDiversified(Savings.PositionLine line) {
        String text = (line.position().label() + " " + line.position().symbol()).toLowerCase(java.util.Locale.ROOT);
        return text.contains("etf") || text.contains("world") || text.contains("monde") || text.contains("msci") || text.contains("s&p") || text.contains("stoxx");
    }

    private static BigDecimal sum(Savings.Overview overview, SavingsKind kind) {
        return overview.lines().stream().filter(line -> line.account().kind() == kind).map(line -> line.account().balance()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal interest(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    }
}
