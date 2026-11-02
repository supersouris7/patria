package fr.patria.portfolio;

import fr.patria.contribution.Contribution;
import fr.patria.contribution.ContributionRepository;
import fr.patria.loan.Loan;
import fr.patria.loan.LoanMath;
import fr.patria.loan.LoanRepository;
import fr.patria.loan.ScheduleLine;
import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.property.Valuation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assemble biens, prets et apports en chiffres prets a afficher. C'est le seul endroit ou ces donnees sont
 * agregees : les pages se contentent d'afficher les resumes produits ici.
 */
public final class Portfolio {
    private final PropertyRepository properties;
    private final LoanRepository loans;
    private final ContributionRepository contributions;
    private final Clock clock;

    public Portfolio(PropertyRepository properties, LoanRepository loans, ContributionRepository contributions, Clock clock) {
        this.properties = properties;
        this.loans = loans;
        this.contributions = contributions;
        this.clock = clock;
    }

    public record LoanSummary(Loan loan, LoanMath.Status status) {
    }

    /** Un bien et tous ses chiffres a la date du jour. */
    public record PropertySummary(Property property, List<LoanSummary> loans, List<Contribution> contributions,
                                  Valuation.Scenarios scenarios, BigDecimal value, String scenario, BigDecimal debt,
                                  BigDecimal borrowed, BigDecimal repaid, BigDecimal contributed, BigDecimal net, BigDecimal gain,
                                  LoanMath.Month nextMonth) {
    }

    /** Patrimoine complet : somme des valeurs retenues de chaque bien. */
    public record Overview(List<PropertySummary> properties, BigDecimal value, BigDecimal net, BigDecimal gain, BigDecimal debt,
                           BigDecimal purchase, BigDecimal contributed, int loanCount, LoanMath.Month nextMonth) {
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public PropertySummary property(long id) {
        Property property = PropertyController.require(properties, id);
        List<Loan> propertyLoans = loans.forProperty(id);
        return summarize(property, propertyLoans, schedulesOf(propertyLoans), contributions.forProperty(id));
    }

    public Overview overview() {
        List<Loan> allLoans = loans.all();
        Map<Long, List<ScheduleLine>> schedules = schedulesOf(allLoans);
        Map<Long, List<Loan>> loansByProperty = allLoans.stream().collect(Collectors.groupingBy(Loan::propertyId));
        Map<Long, List<Contribution>> contributionsByProperty = contributions.all().stream().collect(Collectors.groupingBy(Contribution::propertyId));
        List<PropertySummary> summaries = properties.all().stream().map(property -> summarize(property,
                loansByProperty.getOrDefault(property.id(), List.of()), schedules,
                contributionsByProperty.getOrDefault(property.id(), List.of()))).toList();
        BigDecimal value = sum(summaries, PropertySummary::value);
        BigDecimal purchase = sum(summaries, summary -> summary.property().purchase());
        return new Overview(summaries, value, sum(summaries, PropertySummary::net), value.subtract(purchase), sum(summaries, PropertySummary::debt),
                purchase, sum(summaries, PropertySummary::contributed), allLoans.size(), LoanMath.nextMonth(allLoans, schedules, today()));
    }

    /** Echeances mois par mois d'un bien, tous prets confondus. */
    public List<LoanMath.Month> months(Property property) {
        List<Loan> propertyLoans = loans.forProperty(property.id());
        return LoanMath.months(propertyLoans, schedulesOf(propertyLoans), today());
    }

    private PropertySummary summarize(Property property, List<Loan> propertyLoans, Map<Long, List<ScheduleLine>> schedules, List<Contribution> propertyContributions) {
        LocalDate today = today();
        List<LoanSummary> loanSummaries = propertyLoans.stream()
                .map(loan -> new LoanSummary(loan, LoanMath.status(schedules.getOrDefault(loan.id(), List.of()), today))).toList();
        BigDecimal debt = sumLoans(loanSummaries, LoanMath.Status::remaining);
        Valuation.Scenarios scenarios = Valuation.scenarios(property);
        BigDecimal value = Valuation.retained(property);
        return new PropertySummary(property, loanSummaries, propertyContributions, scenarios, value, scenarios.nameOf(value), debt,
                sumLoans(loanSummaries, LoanMath.Status::borrowed), sumLoans(loanSummaries, LoanMath.Status::repaid),
                propertyContributions.stream().map(Contribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
                Valuation.net(value, property.saleFeesRate(), debt), value.subtract(property.purchase()),
                LoanMath.nextMonth(propertyLoans, schedules, today));
    }

    private Map<Long, List<ScheduleLine>> schedulesOf(List<Loan> list) {
        return loans.schedules(list.stream().map(Loan::id).toList());
    }

    private static BigDecimal sumLoans(List<LoanSummary> summaries, Function<LoanMath.Status, BigDecimal> field) {
        return summaries.stream().map(summary -> field.apply(summary.status())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<PropertySummary> summaries, Function<PropertySummary, BigDecimal> field) {
        return summaries.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
