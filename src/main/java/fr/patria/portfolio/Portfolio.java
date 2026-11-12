// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.portfolio;

import fr.patria.contribution.Contribution;
import fr.patria.contribution.ContributionRepository;
import fr.patria.expense.Expense;
import fr.patria.expense.ExpenseKind;
import fr.patria.expense.ExpenseMath;
import fr.patria.expense.ExpenseRepository;
import fr.patria.loan.Loan;
import fr.patria.loan.LoanMath;
import fr.patria.loan.LoanRepository;
import fr.patria.loan.ScheduleLine;
import fr.patria.market.MarketPrice;
import fr.patria.market.MarketService;
import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.property.Valuation;
import fr.patria.rent.RentMath;
import fr.patria.rent.RentPeriod;
import fr.patria.rent.RentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assemble biens, prets, apports, charges, loyers et prix du marche en chiffres prets a afficher. C'est le seul
 * endroit ou ces donnees sont agregees : les pages se contentent d'afficher les resumes produits ici.
 */
public final class Portfolio {
    /** Sources de donnees de chaque module. */
    /** Estimations successives d'un bien. */
    public List<PropertyRepository.Estimate> estimates(long propertyId) {
        return stores.properties().estimates(propertyId);
    }

    public record Stores(PropertyRepository properties, LoanRepository loans, ContributionRepository contributions,
                         ExpenseRepository expenses, RentRepository rents) {
    }

    private final Stores stores;
    private final MarketService market;
    private final Clock clock;

    public Portfolio(Stores stores, MarketService market, Clock clock) {
        this.stores = stores;
        this.market = market;
        this.clock = clock;
    }

    public record LoanSummary(Loan loan, LoanMath.Status status) {
    }

    /** Un bien et tous ses chiffres a la date du jour. */
    public record PropertySummary(Property property, List<LoanSummary> loans, List<Contribution> contributions,
                                  Valuation.Scenarios scenarios, BigDecimal value, String scenario, BigDecimal debt,
                                  BigDecimal borrowed, BigDecimal repaid, BigDecimal contributed, BigDecimal net, BigDecimal gain,
                                  LoanMath.Month nextMonth, SquareMeter squareMeter, Returns returns, Revaluation revaluation,
                                  boolean estimateOutdated, boolean occupancyMissing) {
    }

    /**
     * Actualisation de l'estimation depuis sa date, selon l'indice DVF du departement.
     *
     * @param factor  coefficient applique (1,05 = +5 %), {@code null} si inconnu (pas de commune, indice indisponible)
     * @param base    estimation prudente telle que saisie (ou cout d'acquisition)
     * @param since   date de l'estimation
     */
    public record Revaluation(BigDecimal factor, BigDecimal base, LocalDate since) {
        /** Variation en %, {@code null} si inconnue. */
        public BigDecimal percent() {
            return factor == null ? null : factor.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        }
    }

    /** Une estimation est jugee ancienne au-dela de ce delai. */
    static final int OUTDATED_ESTIMATE_YEARS = 3;

    /**
     * Prix au m² du bien compare au marche.
     *
     * @param value       valeur retenue / surface ({@code null} sans surface)
     * @param purchase    cout d'acquisition / surface
     * @param market      prix du marche DVF ({@code null} si jamais calcule ou bien sans adresse ni commune)
     * @param gapPercent  ecart du bien par rapport a la mediane du marche, en %
     * @param marketValue mediane du marche × surface
     * @param refreshing  calcul du prix du marche en cours en arriere-plan
     */
    public record SquareMeter(BigDecimal value, BigDecimal purchase, MarketPrice market, BigDecimal gapPercent, BigDecimal marketValue,
                              boolean refreshing) {
    }

    /**
     * Ce que le bien a reellement rapporte ou coute depuis l'achat.
     *
     * @param creditCost    interets et frais des echeances deja passees
     * @param expenses      charges payees depuis l'achat (saisies ou estimees, au prorata)
     * @param yearExpenses  charges de l'annee en cours
     * @param rents         loyers percus depuis l'achat
     * @param savedRent     loyers economises en occupant le bien comme residence principale (loyer de reference)
     * @param monthlyRent   loyer mensuel percu en cours (0 si non loue)
     * @param referenceRent loyer de reference mensuel en cours (0 si le bien n'est pas la residence principale)
     * @param occupancy     usage actuel : « Location », « Résidence principale », « Vacant » ou « Non renseigné »
     * @param invested      argent sorti de la poche : apport + capital rembourse + cout du credit + charges − loyers percus et economises
     * @param realGain      valeur apres frais de vente − cout d'acquisition − cout du credit − charges + loyers percus et economises
     * @param multiple      valeur nette ÷ argent investi ; {@code null} si rien n'a ete investi
     * @param grossYield    loyer annuel en cours ÷ cout d'acquisition, en % ; {@code null} si non loue
     */
    public record Returns(BigDecimal creditCost, BigDecimal expenses, BigDecimal yearExpenses, BigDecimal rents, BigDecimal savedRent,
                          BigDecimal monthlyRent, BigDecimal referenceRent, String occupancy, BigDecimal invested, BigDecimal realGain, BigDecimal multiple, BigDecimal grossYield) {
        public boolean isPrincipalResidence() {
            return "Résidence principale".equals(occupancy);
        }
    }

    /** Patrimoine complet : somme des valeurs retenues de chaque bien. */
    public record Overview(List<PropertySummary> properties, BigDecimal value, BigDecimal net, BigDecimal gain, BigDecimal debt,
                           BigDecimal purchase, BigDecimal contributed, int loanCount, LoanMath.Month nextMonth, Returns returns,
                           List<TaxYear> propertyTaxes, BigDecimal revaluationPercent) {
    }

    /**
     * Total des taxes foncieres d'une annee, tous biens confondus (montant annuel, sans prorata).
     *
     * @param entered    nombre de biens dont le montant de l'annee a ete saisi
     * @param estimated  nombre de biens dont le montant est estime
     * @param evolution  variation en % par rapport a l'annee precedente ({@code null} pour la premiere annee)
     * @param newProperty vrai si un bien de plus est compte par rapport a l'annee precedente (variation non comparable)
     */
    public record TaxYear(int year, BigDecimal amount, int entered, int estimated, BigDecimal evolution, boolean newProperty) {
        public int properties() {
            return entered + estimated;
        }
    }

    /** Ventes retenues pour le prix du marche du bien (sans appel reseau). */
    public java.util.Optional<fr.patria.market.MarketSales> sales(Property property) {
        return market.sales(property);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public PropertySummary property(long id) {
        Property property = PropertyController.require(stores.properties(), id);
        List<Loan> loans = stores.loans().forProperty(id);
        MarketPrice marketPrice = market.forProperty(property).orElse(null);
        return summarize(property, loans, schedulesOf(loans), stores.contributions().forProperty(id), stores.expenses().forProperty(id),
                stores.rents().forProperty(id), marketPrice, market.isComputing(id));
    }

    public Overview overview() {
        List<Loan> allLoans = stores.loans().all();
        Map<Long, List<ScheduleLine>> schedules = schedulesOf(allLoans);
        Map<Long, List<Loan>> loans = allLoans.stream().collect(Collectors.groupingBy(Loan::propertyId));
        Map<Long, List<Contribution>> contributions = stores.contributions().all().stream().collect(Collectors.groupingBy(Contribution::propertyId));
        Map<Long, List<Expense>> expenses = stores.expenses().all().stream().collect(Collectors.groupingBy(Expense::propertyId));
        Map<Long, List<RentPeriod>> rents = stores.rents().all().stream().collect(Collectors.groupingBy(RentPeriod::propertyId));
        List<Property> all = stores.properties().all();
        Map<Long, MarketPrice> prices = market.cached(all.stream().map(Property::id).toList());
        List<PropertySummary> summaries = all.stream().map(property -> summarize(property, loans.getOrDefault(property.id(), List.of()), schedules,
                contributions.getOrDefault(property.id(), List.of()), expenses.getOrDefault(property.id(), List.of()),
                rents.getOrDefault(property.id(), List.of()), prices.get(property.id()), false)).toList();
        BigDecimal value = sum(summaries, PropertySummary::value);
        BigDecimal purchase = sum(summaries, summary -> summary.property().purchase());
        BigDecimal net = sum(summaries, PropertySummary::net);
        BigDecimal invested = sum(summaries, summary -> summary.returns().invested());
        BigDecimal monthlyRent = sum(summaries, summary -> summary.returns().monthlyRent());
        // Rendement brut calcule sur les seuls biens loues : un bien occupe ne doit pas le diluer.
        BigDecimal rentedPurchase = summaries.stream().filter(summary -> summary.returns().monthlyRent().signum() > 0)
                .map(summary -> summary.property().purchase()).reduce(BigDecimal.ZERO, BigDecimal::add);
        long principal = summaries.stream().filter(summary -> summary.returns().isPrincipalResidence()).count();
        long rented = summaries.stream().filter(summary -> summary.returns().monthlyRent().signum() > 0).count();
        String occupancy = (principal > 0 ? "1 résidence principale" : "") + (principal > 0 && rented > 0 ? ", " : "")
                + (rented > 0 ? rented + (rented > 1 ? " biens loués" : " bien loué") : "");
        Returns returns = new Returns(sum(summaries, summary -> summary.returns().creditCost()), sum(summaries, summary -> summary.returns().expenses()),
                sum(summaries, summary -> summary.returns().yearExpenses()), sum(summaries, summary -> summary.returns().rents()),
                sum(summaries, summary -> summary.returns().savedRent()), monthlyRent, sum(summaries, summary -> summary.returns().referenceRent()),
                occupancy.isEmpty() ? "Non renseigné" : occupancy,
                invested, sum(summaries, summary -> summary.returns().realGain()), multiple(net, invested), grossYield(monthlyRent, rentedPurchase));
        return new Overview(summaries, value, net, value.subtract(purchase), sum(summaries, PropertySummary::debt), purchase,
                sum(summaries, PropertySummary::contributed), allLoans.size(), LoanMath.nextMonth(allLoans, schedules, today()), returns,
                propertyTaxes(all, expenses, today()), revaluationPercent(summaries));
    }

    /** Taxes foncieres annee par annee (de la plus recente a la plus ancienne), saisies ou estimees, tous biens confondus. */
    static List<TaxYear> propertyTaxes(List<Property> properties, Map<Long, List<Expense>> expenses, LocalDate today) {
        record Total(BigDecimal amount, int entered, int estimated) {
        }
        TreeMap<Integer, Total> byYear = new TreeMap<>();
        for (Property property : properties) {
            List<Expense> taxes = expenses.getOrDefault(property.id(), List.of()).stream().filter(e -> e.kind() == ExpenseKind.PROPERTY_TAX).toList();
            for (ExpenseMath.YearAmount year : ExpenseMath.years(taxes, property.purchaseDate(), today)) {
                Total current = byYear.getOrDefault(year.year(), new Total(BigDecimal.ZERO, 0, 0));
                byYear.put(year.year(), new Total(current.amount().add(year.amount()), current.entered() + (year.estimated() ? 0 : 1),
                        current.estimated() + (year.estimated() ? 1 : 0)));
            }
        }
        List<TaxYear> result = new ArrayList<>();
        Total previous = null;
        for (Map.Entry<Integer, Total> entry : byYear.entrySet()) {
            Total total = entry.getValue();
            BigDecimal evolution = previous == null || previous.amount().signum() == 0 ? null
                    : total.amount().subtract(previous.amount()).multiply(BigDecimal.valueOf(100)).divide(previous.amount(), 1, RoundingMode.HALF_UP);
            boolean newProperty = previous != null && total.entered() + total.estimated() > previous.entered() + previous.estimated();
            result.add(new TaxYear(entry.getKey(), total.amount(), total.entered(), total.estimated(), evolution, newProperty));
            previous = total;
        }
        return result.reversed();
    }

    /** Echeances mois par mois d'un bien, tous prets confondus. */
    public List<LoanMath.Month> months(Property property) {
        List<Loan> loans = stores.loans().forProperty(property.id());
        return LoanMath.months(loans, schedulesOf(loans), today());
    }

    private PropertySummary summarize(Property property, List<Loan> loans, Map<Long, List<ScheduleLine>> schedules, List<Contribution> contributions,
                                      List<Expense> expenses, List<RentPeriod> rents, MarketPrice marketPrice, boolean marketRefreshing) {
        LocalDate today = today();
        List<LoanSummary> loanSummaries = loans.stream()
                .map(loan -> new LoanSummary(loan, LoanMath.status(schedules.getOrDefault(loan.id(), List.of()), today))).toList();
        BigDecimal debt = sumLoans(loanSummaries, LoanMath.Status::remaining);
        BigDecimal borrowed = sumLoans(loanSummaries, LoanMath.Status::borrowed);
        BigDecimal repaid = sumLoans(loanSummaries, LoanMath.Status::repaid);
        BigDecimal contributed = contributions.stream().map(Contribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal factor = marketPrice == null ? null : marketPrice.revaluation();
        Revaluation revaluation = new Revaluation(factor, Valuation.base(property), property.estimateDate());
        BigDecimal value = Valuation.retained(property, factor == null ? BigDecimal.ONE : factor);
        SquareMeter squareMeter = squareMeter(property, value, marketPrice, marketRefreshing);
        Valuation.Scenarios scenarios = Valuation.scenarios(property, factor == null ? BigDecimal.ONE : factor).withMarket(squareMeter.marketValue());
        BigDecimal net = Valuation.net(value, property.saleFeesRate(), debt);
        Returns returns = returns(property, value, net, borrowed, repaid, contributed, sumLoans(loanSummaries, LoanMath.Status::creditCost), expenses, rents, today);
        return new PropertySummary(property, loanSummaries, contributions, scenarios, value, scenarios.nameOf(value), debt, borrowed, repaid,
                contributed, net, value.subtract(property.purchase()), LoanMath.nextMonth(loans, schedules, today),
                squareMeter, returns, revaluation,
                property.estimateDate().plusYears(OUTDATED_ESTIMATE_YEARS).isBefore(today), rents.isEmpty());
    }

    /** Actualisation globale : estimations actualisees ÷ estimations saisies, sur les biens dont l'indice est connu. */
    private static BigDecimal revaluationPercent(List<PropertySummary> summaries) {
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal revalued = BigDecimal.ZERO;
        for (PropertySummary summary : summaries) {
            if (summary.revaluation().factor() == null) continue;
            base = base.add(summary.revaluation().base());
            revalued = revalued.add(summary.revaluation().base().multiply(summary.revaluation().factor()));
        }
        if (base.signum() == 0) return null;
        return revalued.divide(base, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * Rentabilite reelle. L'apport retenu est la somme des apports saisis ; a defaut, la part du cout d'acquisition
     * non financee par les prets.
     */
    static Returns returns(Property property, BigDecimal value, BigDecimal net, BigDecimal borrowed, BigDecimal repaid, BigDecimal contributed,
                           BigDecimal creditCost, List<Expense> expenses, List<RentPeriod> rents, LocalDate today) {
        BigDecimal expensesPaid = ExpenseMath.paidToDate(expenses, property.purchaseDate(), today);
        BigDecimal rentsReceived = RentMath.receivedToDate(rents, today);
        BigDecimal savedRent = RentMath.savedToDate(rents, today);
        BigDecimal monthlyRent = RentMath.currentMonthly(rents, today);
        RentPeriod current = RentMath.currentPeriod(rents, today);
        String occupancy = current != null ? current.kind().label() : rents.isEmpty() ? "Non renseigné" : "Vacant";
        BigDecimal initialCash = contributed.signum() > 0 ? contributed : property.purchase().subtract(borrowed).max(BigDecimal.ZERO);
        // Le loyer economise est traite comme un loyer percu : c'est une depense de logement evitee.
        BigDecimal invested = initialCash.add(repaid).add(creditCost).add(expensesPaid).subtract(rentsReceived).subtract(savedRent);
        BigDecimal afterSale = value.subtract(value.multiply(property.saleFeesRate()));
        BigDecimal realGain = afterSale.subtract(property.purchase()).subtract(creditCost).subtract(expensesPaid).add(rentsReceived).add(savedRent)
                .setScale(2, RoundingMode.HALF_UP);
        return new Returns(creditCost, expensesPaid, ExpenseMath.currentYear(expenses, property.purchaseDate(), today), rentsReceived, savedRent,
                monthlyRent, RentMath.currentReference(rents, today), occupancy, invested, realGain, multiple(net, invested), grossYield(monthlyRent, property.purchase()));
    }

    private static BigDecimal multiple(BigDecimal net, BigDecimal invested) {
        return invested.signum() > 0 ? net.divide(invested, 2, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal grossYield(BigDecimal monthlyRent, BigDecimal purchase) {
        if (monthlyRent.signum() == 0 || purchase.signum() == 0) return null;
        return monthlyRent.multiply(BigDecimal.valueOf(1200)).divide(purchase, 1, RoundingMode.HALF_UP);
    }

    static SquareMeter squareMeter(Property property, BigDecimal value, MarketPrice marketPrice, boolean refreshing) {
        BigDecimal area = property.area();
        BigDecimal valuePerSqm = perSquareMeter(value, area);
        BigDecimal purchasePerSqm = property.purchase().signum() == 0 ? null : perSquareMeter(property.purchase(), area);
        boolean comparable = marketPrice != null && marketPrice.isAvailable();
        BigDecimal gap = comparable && valuePerSqm != null
                ? valuePerSqm.subtract(marketPrice.median()).multiply(BigDecimal.valueOf(100)).divide(marketPrice.median(), 1, RoundingMode.HALF_UP) : null;
        BigDecimal marketValue = comparable && area != null ? marketPrice.median().multiply(area).setScale(0, RoundingMode.HALF_UP) : null;
        return new SquareMeter(valuePerSqm, purchasePerSqm, marketPrice, gap, marketValue, refreshing);
    }

    private static BigDecimal perSquareMeter(BigDecimal amount, BigDecimal area) {
        return area == null || area.signum() == 0 ? null : amount.divide(area, 0, RoundingMode.HALF_UP);
    }

    private Map<Long, List<ScheduleLine>> schedulesOf(List<Loan> list) {
        return stores.loans().schedules(list.stream().map(Loan::id).toList());
    }

    private static BigDecimal sumLoans(List<LoanSummary> summaries, Function<LoanMath.Status, BigDecimal> field) {
        return summaries.stream().map(summary -> field.apply(summary.status())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<PropertySummary> summaries, Function<PropertySummary, BigDecimal> field) {
        return summaries.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
