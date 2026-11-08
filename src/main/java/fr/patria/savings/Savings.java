package fr.patria.savings;

import fr.patria.household.Income;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Calculs de l'epargne, en fonctions pures : valeur des comptes et des lignes de bourse, plan d'epargne de securite
 * (precaution puis repartition), evolution du revenu et pouvoir d'achat. Indicatif, pas un conseil en investissement.
 */
public final class Savings {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Une seule ligne au-dela de cette part du portefeuille : risque de concentration. */
    public static final BigDecimal CONCENTRATION = BigDecimal.valueOf(40);

    private Savings() {
    }

    /** Une ligne de bourse valorisee : cours du jour (API), saisi, ou a defaut le prix de revient. */
    public record PositionLine(SavingsRepository.Position position, BigDecimal price, String priceSource, BigDecimal value, BigDecimal gain) {
    }

    /**
     * Un placement valorise : {@code balance} solde du jour (solde saisi + versements programmes depuis, plafond
     * respecte ; liquidites pour un PEA ou un compte-titres), {@code added} la part ajoutee par les versements,
     * {@code value} solde + lignes de bourse, taux et interets annuels.
     */
    public record Line(SavingsRepository.Account account, String owner, BigDecimal balance, BigDecimal added, BigDecimal value, BigDecimal rate,
                       BigDecimal yearlyInterest, List<PositionLine> positions) {
    }

    /** Solde du jour : le solde saisi, plus chaque versement programme echu depuis (au plus le plafond d'un livret). */
    public static BigDecimal balance(SavingsRepository.Account account, LocalDate today) {
        SavingsRepository.Contribution contribution = account.contribution();
        BigDecimal balance = account.balance();
        if (contribution == null || contribution.frequency() == fr.patria.common.Frequency.ONCE) return balance;
        java.time.YearMonth first = java.time.YearMonth.from(contribution.start());
        java.time.YearMonth month = java.time.YearMonth.from(account.balanceDate()).plusMonths(1);
        if (month.isBefore(first)) month = first;
        java.time.YearMonth current = java.time.YearMonth.from(today);
        while (!month.isAfter(current)) {
            boolean due = month.isBefore(current) || today.getDayOfMonth() >= Math.min(contribution.start().getDayOfMonth(), month.lengthOfMonth());
            if (due && contribution.frequency().occursIn(first, null, month)) balance = balance.add(contribution.amount());
            month = month.plusMonths(1);
        }
        BigDecimal ceiling = account.kind().ceiling();
        return ceiling != null && account.kind().regulated() && balance.compareTo(ceiling) > 0 ? ceiling.max(account.balance()) : balance;
    }

    public record Overview(List<Line> lines, BigDecimal total, BigDecimal available, BigDecimal stocks, BigDecimal stockGain) {
        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public List<PositionLine> positions() {
            return lines.stream().flatMap(line -> line.positions().stream()).toList();
        }
    }

    public static Overview overview(List<SavingsRepository.Account> accounts, List<SavingsRepository.Position> positions, Map<Long, String> owners,
                                    Function<String, Optional<MarketFeeds.Quote>> quotes, LocalDate today) {
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal stocks = BigDecimal.ZERO;
        BigDecimal gain = BigDecimal.ZERO;
        for (SavingsRepository.Account account : accounts) {
            List<PositionLine> held = new ArrayList<>();
            BigDecimal current = balance(account, today);
            BigDecimal value = current;
            if (account.kind().stocks()) {
                for (SavingsRepository.Position position : positions) {
                    if (position.accountId() != account.id()) continue;
                    Optional<MarketFeeds.Quote> quote = quotes.apply(position.symbol());
                    Optional<BigDecimal> fx = quote.filter(found -> !found.currency().equals("EUR"))
                            .flatMap(found -> quotes.apply(found.currency() + "EUR=X")).map(MarketFeeds.Quote::price);
                    PositionLine line = value(position, quote, fx);
                    held.add(line);
                    value = value.add(line.value());
                    stocks = stocks.add(line.value());
                    gain = gain.add(line.gain());
                }
            }
            BigDecimal rate = rate(account, today);
            BigDecimal interest = rate == null ? null : current.multiply(rate).divide(HUNDRED, 0, RoundingMode.HALF_UP);
            lines.add(new Line(account, owners.getOrDefault(account.personId(), "?"), current, current.subtract(account.balance()), value, rate, interest, held));
            total = total.add(value);
            if (account.kind().available()) available = available.add(current);
        }
        return new Overview(lines, total, available, stocks, gain);
    }

    /** Taux du placement : saisi s'il l'est, sinon celui du livret reglemente en vigueur. */
    public static BigDecimal rate(SavingsRepository.Account account, LocalDate today) {
        if (account.rate() != null) return account.rate();
        if (account.kind() == SavingsKind.CURRENT) return BigDecimal.ZERO;
        return RegulatedRates.at(account.kind() == SavingsKind.LIVRET_JEUNE ? SavingsKind.LIVRET_A : account.kind(), today)
                .map(RegulatedRates.Rate::percent).orElse(null);
    }

    static PositionLine value(SavingsRepository.Position position, Optional<MarketFeeds.Quote> quote) {
        return value(position, quote, Optional.empty());
    }

    /** {@code rate} : cours de la devise de cotation en euros (ex. USDEUR=X), pour une valeur cotee hors euro. */
    static PositionLine value(SavingsRepository.Position position, Optional<MarketFeeds.Quote> quote, Optional<BigDecimal> rate) {
        BigDecimal price;
        String source;
        if (position.price() != null) {
            price = position.price();
            source = "saisi";
        } else if (quote.isPresent() && quote.get().currency().equals("EUR")) {
            price = quote.get().price();
            source = "cours du jour";
        } else if (quote.isPresent() && rate.isPresent()) {
            price = quote.get().price().multiply(rate.get()).setScale(4, RoundingMode.HALF_UP);
            source = "cours du jour, converti depuis " + quote.get().currency();
        } else {
            price = position.cost().divide(position.quantity(), 4, RoundingMode.HALF_UP);
            source = quote.isPresent() ? "prix de revient (cotée en " + quote.get().currency() + ")" : "prix de revient";
        }
        BigDecimal value = price.multiply(position.quantity()).setScale(2, RoundingMode.HALF_UP);
        return new PositionLine(position, price, source, value, value.subtract(position.cost()));
    }

    /** Une etape du plan : ou placer combien, et pourquoi. */
    public record Step(String where, BigDecimal amount, BigDecimal rate, String why) {
    }

    /**
     * Plan d'epargne de securite. {@code months} : objectif de precaution en mois de depenses ; {@code lep} :
     * « oui », « non » ou vide (inconnu). {@code holders} : membres (personnes) qui peuvent ouvrir des livrets.
     */
    public record Plan(BigDecimal spending, int months, BigDecimal target, BigDecimal available, BigDecimal missing, BigDecimal monthly,
                       BigDecimal margin, List<Step> steps) {
        public BigDecimal coverMonths() {
            return spending.signum() == 0 ? BigDecimal.ZERO : available.divide(spending, 1, RoundingMode.HALF_DOWN);
        }
    }

    public static Plan plan(BigDecimal spending, BigDecimal margin, Overview overview, Collection<Long> holders, int months, String lep, LocalDate today) {
        BigDecimal target = spending.multiply(BigDecimal.valueOf(months)).setScale(0, RoundingMode.HALF_UP);
        BigDecimal missing = target.subtract(overview.available()).max(BigDecimal.ZERO);
        // Versement conseille : combler en un an, sans depasser la moitie de ce qui reste chaque mois.
        BigDecimal monthly = BigDecimal.ZERO;
        if (missing.signum() > 0 && margin.signum() > 0) {
            monthly = missing.divide(BigDecimal.valueOf(12), 0, RoundingMode.UP).min(margin.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN));
            monthly = monthly.divide(BigDecimal.TEN, 0, RoundingMode.DOWN).multiply(BigDecimal.TEN).max(BigDecimal.TEN);
        }
        List<Step> steps = new ArrayList<>();
        BigDecimal left = missing;
        List<SavingsKind> order = new ArrayList<>();
        if (!"non".equals(lep)) order.add(SavingsKind.LEP);
        order.add(SavingsKind.LIVRET_A);
        order.add(SavingsKind.LDDS);
        for (SavingsKind kind : holders.isEmpty() ? List.<SavingsKind>of() : order) {
            BigDecimal room = room(kind, overview, holders);
            BigDecimal rate = RegulatedRates.at(kind, today).map(RegulatedRates.Rate::percent).orElse(null);
            BigDecimal put = left.min(room);
            String why = switch (kind) {
                case LEP -> "Le mieux rémunéré des livrets" + ("oui".equals(lep) ? "" : ", si votre revenu fiscal le permet (" + euro(RegulatedRates.LEP_INCOME_ONE_PART)
                        + " pour 1 part, + " + euro(RegulatedRates.LEP_INCOME_HALF_PART) + " par demi-part)") + ". Plafond " + euro(kind.ceiling()) + " par personne.";
                case LIVRET_A -> "Disponible à tout moment, sans impôt. Plafond " + euro(kind.ceiling()) + " par personne.";
                default -> "Mêmes avantages que le Livret A. Plafond " + euro(kind.ceiling()) + " par personne.";
            };
            if (put.signum() > 0) {
                steps.add(new Step(kind.label(), put, rate, why));
                left = left.subtract(put);
            } else if (missing.signum() > 0 && room.signum() == 0 && kind != SavingsKind.LEP) {
                steps.add(new Step(kind.label(), BigDecimal.ZERO, rate, "Plafond atteint pour chacun : passez à l'étape suivante."));
            }
        }
        if (left.signum() > 0) steps.add(new Step("Autre livret bancaire", left, null, "Les livrets réglementés sont pleins : un livret bancaire garde l'argent disponible."));
        steps.add(new Step("Long terme : assurance-vie, PEA", null, null,
                "Une fois la précaution atteinte, l'épargne qui peut attendre 5 ans ou plus a plus de chances d'être rémunérée au-dessus de l'inflation, avec un risque de perte."));
        return new Plan(spending, months, target, overview.available(), missing, monthly, margin, steps);
    }

    /** Place restante sous le plafond d'un livret, pour l'ensemble des personnes (un livret par personne). */
    static BigDecimal room(SavingsKind kind, Overview overview, Collection<Long> holders) {
        BigDecimal room = BigDecimal.ZERO;
        for (long holder : holders) {
            BigDecimal held = overview.lines().stream().filter(line -> line.account().personId() == holder && line.account().kind() == kind)
                    .map(Line::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
            room = room.add(kind.ceiling().subtract(held).max(BigDecimal.ZERO));
        }
        return room;
    }

    /** Revenus mensuels (equivalent mensuel) a une date, l'an dernier, l'evolution et le pouvoir d'achat face a l'inflation. */
    public record PurchasingPower(BigDecimal now, BigDecimal yearAgo, BigDecimal income, BigDecimal inflation, BigDecimal power) {
    }

    public static Optional<PurchasingPower> purchasingPower(List<Income> incomes, LocalDate today, Optional<MarketFeeds.Inflation> inflation) {
        BigDecimal now = monthlyIncome(incomes, today);
        BigDecimal before = monthlyIncome(incomes, today.minusYears(1));
        if (inflation.isEmpty() || now.signum() == 0 || before.signum() == 0) return Optional.empty();
        BigDecimal evolution = now.divide(before, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        BigDecimal prices = inflation.get().percent().divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal power = BigDecimal.ONE.add(evolution).divide(BigDecimal.ONE.add(prices), 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        return Optional.of(new PurchasingPower(now, before, evolution.movePointRight(2).setScale(1, RoundingMode.HALF_UP), inflation.get().percent(),
                power.movePointRight(2).setScale(1, RoundingMode.HALF_UP)));
    }

    static BigDecimal monthlyIncome(List<Income> incomes, LocalDate date) {
        return incomes.stream().filter(income -> !income.start().isAfter(date) && (income.end() == null || !income.end().isBefore(date)))
                .filter(income -> income.frequency() != fr.patria.common.Frequency.ONCE)
                .map(income -> income.frequency().monthly(income.amount())).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(0, RoundingMode.HALF_UP);
    }

    /** Membres proprietaires, par identifiant (pour afficher « Livret A de Paul »). */
    public static Map<Long, String> owners(List<fr.patria.household.Person> people) {
        Map<Long, String> owners = new LinkedHashMap<>();
        people.forEach(person -> owners.put(person.id(), person.name()));
        return owners;
    }

    private static String euro(BigDecimal amount) {
        return fr.patria.ui.Format.euroWhole(amount);
    }
}
