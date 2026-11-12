// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.expense.Expense;
import fr.patria.expense.ExpenseKind;
import fr.patria.expense.ExpenseMath;
import fr.patria.expense.ExpenseRepository;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Income;
import fr.patria.household.Person;
import fr.patria.household.ShareRepository;
import fr.patria.household.Shares;
import fr.patria.household.Transfer;
import fr.patria.loan.LineType;
import fr.patria.loan.Loan;
import fr.patria.loan.LoanRepository;
import fr.patria.loan.ScheduleLine;
import fr.patria.property.Property;
import fr.patria.property.PropertyRepository;
import fr.patria.rent.Occupancy;
import fr.patria.rent.RentMath;
import fr.patria.rent.RentPeriod;
import fr.patria.rent.RentRepository;
import fr.patria.ui.Format;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Previsionnel du foyer sur les 12 prochains mois : revenus et depenses mois par mois, taux d'endettement,
 * epargne de precaution pour les depenses non mensuelles et alertes.
 *
 * <p>Les lignes viennent de deux sources : la saisie du budget (revenus, depenses) et l'immobilier, repris
 * automatiquement (mensualites de pret, charges des biens, loyers percus). Mois de paiement retenus pour les
 * charges des biens : taxe fonciere en octobre (ou de janvier a octobre si mensualisee ; au membre qui la paie s'il est choisi), taxe d'habitation en novembre, copropriete par trimestre
 * (janvier, avril, juillet, octobre), assurance et autres charges reparties sur 12 mois.
 */
public final class Forecast {
    /** Seuil d'endettement du Haut Conseil de stabilite financiere (HCSF). */
    public static final BigDecimal DEBT_LIMIT = new BigDecimal("35");
    /** En dessous : endettement confortable (affiche en vert). Entre ce seuil et la limite : vigilance (orange). */
    public static final BigDecimal DEBT_COMFORT = new BigDecimal("33");
    /** Part des loyers percus retenue par les banques. */
    private static final BigDecimal RENT_SHARE = new BigDecimal("0.70");
    /** Une depense non mensuelle est signalee a partir de ce montant. */
    private static final BigDecimal ALERT_THRESHOLD = new BigDecimal("100");
    private static final int HORIZON = 12;
    private static final int ALERT_MONTHS = 3;

    public record Sources(HouseholdRepository household, BudgetRepository items, PropertyRepository properties, LoanRepository loans,
                          ExpenseRepository expenses, RentRepository rents, ShareRepository shares) {
    }

    /** Provenance d'une ligne : saisie du budget ou reprise de l'immobilier. */
    public enum Origin { BUDGET, REAL_ESTATE }

    /**
     * Depense d'un mois.
     *
     * @param link page ou la ligne se modifie
     */
    public record Line(String label, Category category, BigDecimal amount, Frequency frequency, Origin origin, String link) {
    }

    /** Revenu d'un mois ; {@code who} est la personne, ou « Immobilier » pour un loyer percu. */
    public record IncomeLine(String label, String who, BigDecimal amount, Frequency frequency, Origin origin, String link) {
    }

    public record Month(YearMonth month, List<IncomeLine> incomes, List<Line> expenses) {
        public BigDecimal income() {
            return incomes.stream().map(IncomeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal spending() {
            return expenses.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Reste a vivre : revenus − depenses. */
        public BigDecimal balance() {
            return income().subtract(spending());
        }

        public Map<Category.Family, BigDecimal> byFamily() {
            Map<Category.Family, BigDecimal> totals = new EnumMap<>(Category.Family.class);
            for (Line line : expenses) totals.merge(line.category().family(), line.amount(), BigDecimal::add);
            return totals;
        }
    }

    /**
     * Taux d'endettement, methode bancaire (HCSF) : mensualites de credit (assurance comprise) ÷ revenus nets
     * avant impot, loyers percus comptes a 70 %.
     *
     * @param credits mensualites moyennes sur 12 mois
     * @param income  revenus mensuels recurrents en cours (primes annuelles divisees par 12)
     * @param percent {@code null} sans revenus
     */
    public record DebtRatio(BigDecimal credits, BigDecimal income, BigDecimal percent) {
        /** Vert sous 33 %, orange de 33 a 35 %, rouge au-dela. */
        public enum Level { GOOD, WARNING, SERIOUS }

        public boolean isAboveLimit() {
            return percent != null && percent.compareTo(DEBT_LIMIT) > 0;
        }

        /** {@code null} sans revenus. */
        public Level level() {
            if (percent == null) return null;
            if (isAboveLimit()) return Level.SERIOUS;
            return percent.compareTo(DEBT_COMFORT) < 0 ? Level.GOOD : Level.WARNING;
        }

        /** Mensualite supplementaire possible avant d'atteindre 33 % (zero si deja atteint). */
        public BigDecimal marginToComfort() {
            if (percent == null) return BigDecimal.ZERO;
            BigDecimal room = income.multiply(DEBT_COMFORT).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).subtract(credits);
            return room.max(BigDecimal.ZERO);
        }
    }

    public record Alert(String text, boolean serious) {
    }

    /**
     * @param provision moyenne mensuelle des depenses non mensuelles : a mettre de cote chaque mois pour ne pas etre surpris
     */
    /**
     * @param person  personne affichee, {@code null} pour le foyer entier
     * @param people  personnes proposees dans le selecteur (toutes, ou celles dont la vue est ouverte)
     * @param householdTab onglet « Foyer » propose dans le selecteur
     */
    public record View(List<Month> months, DebtRatio debt, BigDecimal provision, List<Alert> alerts, boolean hasIncome, boolean hasExpenses,
                       Person person, List<Person> people, boolean householdTab) {
        public Month current() {
            return months.getFirst();
        }

        /** Meme vue, selecteur limite aux vues ouvertes d'un utilisateur. */
        public View withTabs(boolean household, List<Person> open) {
            return new View(months, debt, provision, alerts, hasIncome, hasExpenses, person, open, household);
        }
    }

    /** Ligne du budget moyen : une depense ou un revenu ramene a son equivalent mensuel sur 12 mois. */
    public record Average(String label, String detail, Category category, BigDecimal perPayment, Frequency frequency, BigDecimal monthly,
                          Origin origin, String link) {
    }

    private final Sources sources;
    private final Clock clock;

    public Forecast(Sources sources, Clock clock) {
        this.sources = sources;
        this.clock = clock;
    }

    /**
     * Mois du foyer entier entre {@code from} et {@code to} inclus, passes ou futurs, d'apres les lignes datees (un
     * montant change « a partir de » une date garde l'ancien avant) : sert a reconstituer l'historique.
     */
    public List<Month> months(YearMonth from, YearMonth to) {
        Data data = load();
        Scope scope = new Scope(null, null, Set.of(), data);
        List<Month> result = new ArrayList<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) result.add(month(month, data, scope));
        return result;
    }

    /** Previsionnel du foyer entier. */
    public View next12Months() {
        return next12Months(null);
    }

    /**
     * Previsionnel d'une personne : ses revenus et sa part de chaque depense, bien et pret. {@code null} ou une
     * personne inconnue donne le foyer entier.
     */
    public View next12Months(Long personId) {
        return next12Months(personId, Set.of());
    }

    /**
     * Comme {@link #next12Months(Long)} ; dans la vue du foyer, les lignes qui ne concernent que des membres
     * {@code hidden} (masques a un gestionnaire) gardent leur montant mais perdent libelle et lien.
     */
    public View next12Months(Long personId, Set<Long> hidden) {
        Data data = load();
        Person person = personId == null ? null : data.people().stream().filter(p -> p.id() == personId).findFirst().orElse(null);
        return build(new Scope(person, null, hidden, data), person, data);
    }

    /**
     * Previsionnel d'un groupe de membres (perimetre du bilan : soi, les comptes joints, les comptes choisis) : leurs
     * revenus, leur part de chaque depense ; les virements entre eux s'annulent.
     */
    public View next12MonthsOf(Set<Long> group) {
        Data data = load();
        if (group.size() == 1) {
            Person person = data.people().stream().filter(p -> group.contains(p.id())).findFirst().orElse(null);
            return build(new Scope(person, null, Set.of(), data), person, data);
        }
        return build(new Scope(null, Set.copyOf(group), Set.of(), data), null, data);
    }

    private View build(Scope scope, Person person, Data data) {
        LocalDate today = LocalDate.now(clock);
        List<Month> months = new ArrayList<>();
        for (int i = 0; i < HORIZON; i++) months.add(month(YearMonth.from(today).plusMonths(i), data, scope));
        DebtRatio debt = debtRatio(months, data, scope, today, jointCredits(data, person, today));
        BigDecimal provision = months.stream().flatMap(month -> month.expenses().stream()).filter(line -> line.frequency() != Frequency.MONTHLY)
                .map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(HORIZON), 0, RoundingMode.HALF_UP);
        boolean hasIncome = data.incomes().stream().anyMatch(scope::includes) || months.stream().anyMatch(m -> !m.incomes().isEmpty());
        return new View(months, debt, provision, alerts(months, debt), hasIncome, months.stream().anyMatch(m -> !m.expenses().isEmpty()), person, data.people(), true);
    }

    /** Revenus et depenses ramenes a leur moyenne mensuelle sur les 12 prochains mois, ligne par ligne. */
    public List<Average> averageExpenses(View view) {
        Map<String, List<Line>> byLabel = view.months().stream().flatMap(month -> month.expenses().stream())
                .collect(Collectors.groupingBy(line -> line.origin() + "|" + line.label(), LinkedHashMap::new, Collectors.toList()));
        return byLabel.values().stream().map(lines -> {
            Line first = lines.getFirst();
            BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Average(first.label(), first.category().label(), first.category(), total.divide(BigDecimal.valueOf(lines.size()), 2, RoundingMode.HALF_UP),
                    first.frequency(), total.divide(BigDecimal.valueOf(HORIZON), 2, RoundingMode.HALF_UP), first.origin(), first.link());
        }).toList();
    }

    public List<Average> averageIncomes(View view) {
        Map<String, List<IncomeLine>> byLabel = view.months().stream().flatMap(month -> month.incomes().stream())
                .collect(Collectors.groupingBy(line -> line.who() + "|" + line.label(), LinkedHashMap::new, Collectors.toList()));
        return byLabel.values().stream().map(lines -> {
            IncomeLine first = lines.getFirst();
            BigDecimal total = lines.stream().map(IncomeLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Average(first.label(), first.who(), null, total.divide(BigDecimal.valueOf(lines.size()), 2, RoundingMode.HALF_UP),
                    first.frequency(), total.divide(BigDecimal.valueOf(HORIZON), 2, RoundingMode.HALF_UP), first.origin(), first.link());
        }).toList();
    }

    // ---- Construction des mois ----

    private record Data(List<Person> people, List<Income> incomes, List<BudgetItem> items, List<Property> properties, List<Loan> loans,
                        Map<Long, List<ScheduleLine>> schedules, Map<Long, List<Expense>> charges, Map<Long, List<RentPeriod>> rents,
                        Map<Shares.Key, Map<Long, Integer>> shares, List<Transfer> transfers, Set<Long> monthlyTax,
                        Map<Long, Long> taxPayers) {
        String name(long memberId) {
            return people.stream().filter(person -> person.id() == memberId).map(Person::name).findFirst().orElse("");
        }
    }

    /**
     * Ce que voit la vue : tout le foyer (part 1 partout) ou une personne (ses revenus, et sa part de chaque
     * element). Un pret sans repartition propre suit celle de son bien.
     */
    private record Scope(Person person, Set<Long> group, Set<Long> hidden, Data data) {
        /** Le membre fait partie de la vue (le foyer entier : tous). */
        boolean has(long memberId) {
            return person != null ? person.id() == memberId : group == null || group.contains(memberId);
        }

        boolean includes(Income income) {
            return has(income.personId());
        }

        /** Tout pour un seul membre (payeur choisi) : 1 s'il fait partie de la vue, 0 sinon. */
        BigDecimal member(long memberId) {
            return has(memberId) ? BigDecimal.ONE : BigDecimal.ZERO;
        }

        BigDecimal weight(Shares.Subject subject, long id) {
            if (person == null && group == null) return BigDecimal.ONE;
            Map<Long, Integer> shares = data.shares().getOrDefault(new Shares.Key(subject, id), Map.of());
            if (person != null) return Shares.weight(shares, data.people(), person.id());
            return data.people().stream().filter(member -> group.contains(member.id())).map(member -> Shares.weight(shares, data.people(), member.id()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).min(BigDecimal.ONE);
        }

        /** Vue du foyer : ligne masquee si tous ceux qui la paient sont masques a ce gestionnaire. */
        boolean masked(Shares.Subject subject, long id) {
            if (hidden.isEmpty() || person != null || group != null) return false;
            Map<Long, Integer> shares = data.shares().getOrDefault(new Shares.Key(subject, id), Map.of());
            List<Person> holders = data.people().stream().filter(member -> Shares.weight(shares, data.people(), member.id()).signum() > 0).toList();
            return !holders.isEmpty() && holders.stream().allMatch(member -> hidden.contains(member.id()));
        }

        boolean maskedIncome(Income income) {
            return person == null && group == null && hidden.contains(income.personId());
        }

        BigDecimal loan(Loan loan) {
            Map<Long, Integer> own = data.shares().getOrDefault(new Shares.Key(Shares.Subject.LOAN, loan.id()), Map.of());
            return Shares.isEqual(own, data.people()) ? weight(Shares.Subject.PROPERTY, loan.propertyId()) : weight(Shares.Subject.LOAN, loan.id());
        }

        /** Applique la part : montant reduit, part indiquee dans le libelle. {@code null} si la part est nulle. */
        Line apply(Line line, BigDecimal weight) {
            if (weight.signum() == 0) return null;
            if (weight.compareTo(BigDecimal.ONE) == 0) return line;
            return new Line(line.label() + " (" + Shares.percent(weight) + ")", line.category(), line.amount().multiply(weight).setScale(2, RoundingMode.HALF_UP),
                    line.frequency(), line.origin(), line.link());
        }
    }

    private Data load() {
        List<Loan> loans = sources.loans().all();
        return new Data(sources.household().people(), sources.household().incomes(), sources.items().all(), sources.properties().all(), loans,
                sources.loans().schedules(loans.stream().map(Loan::id).toList()),
                sources.expenses().all().stream().collect(Collectors.groupingBy(Expense::propertyId)),
                sources.rents().all().stream().collect(Collectors.groupingBy(RentPeriod::propertyId)), sources.shares().all(),
                sources.household().transfers(), sources.expenses().monthlyPropertyTax(), sources.shares().propertyTaxPayers());
    }

    private static Month month(YearMonth month, Data data, Scope scope) {
        List<IncomeLine> incomes = new ArrayList<>();
        for (Income income : data.incomes()) {
            if (!income.paidIn(month) || !scope.includes(income)) continue;
            if (scope.maskedIncome(income)) {
                incomes.add(new IncomeLine("Revenu masqué", "", income.amount(), income.frequency(), Origin.BUDGET, null));
                continue;
            }
            String who = data.people().stream().filter(person -> person.id() == income.personId()).map(Person::name).findFirst().orElse("");
            incomes.add(new IncomeLine(income.label(), who, income.amount(), income.frequency(), Origin.BUDGET, "/revenu/" + income.id()));
        }
        List<Line> expenses = new ArrayList<>();
        for (BudgetItem item : data.items()) {
            if (!item.dueIn(month)) continue;
            boolean masked = scope.masked(Shares.Subject.BUDGET_ITEM, item.id());
            add(expenses, scope.apply(new Line(masked ? "Ligne masquée" : item.label(), item.category(), item.amount(), item.frequency(), Origin.BUDGET,
                    masked ? null : "/depense/" + item.id()), scope.weight(Shares.Subject.BUDGET_ITEM, item.id())));
        }
        // Virements : depense pour l'emetteur, recette pour le destinataire ; internes, donc absents de la vue du foyer
        if (scope.person() != null || scope.group() != null) {
            for (Transfer transfer : data.transfers()) {
                if (!transfer.paidIn(month)) continue;
                String link = "/virement/" + transfer.id();
                // Un virement entre deux membres de la vue s'annule.
                if (scope.has(transfer.fromId()) && scope.has(transfer.toId())) continue;
                if (scope.has(transfer.fromId())) {
                    String to = data.name(transfer.toId());
                    expenses.add(new Line(transfer.label().isBlank() ? "Virement vers " + to : transfer.label() + " (vers " + to + ")", Category.TRANSFER,
                            transfer.amount(), transfer.frequency(), Origin.BUDGET, link));
                } else if (scope.has(transfer.toId())) {
                    String from = data.name(transfer.fromId());
                    incomes.add(new IncomeLine(transfer.label().isBlank() ? "Virement de " + from : transfer.label() + " (de " + from + ")", from,
                            transfer.amount(), transfer.frequency(), Origin.BUDGET, link));
                }
            }
        }
        Map<Long, String> propertyNames = data.properties().stream().collect(Collectors.toMap(Property::id, Property::name));
        for (Loan loan : data.loans()) {
            BigDecimal installments = data.schedules().getOrDefault(loan.id(), List.of()).stream()
                    .filter(line -> line.type() == LineType.INSTALLMENT && line.amount() != null && YearMonth.from(line.date()).equals(month))
                    .map(ScheduleLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (installments.signum() > 0) {
                add(expenses, scope.apply(new Line("Mensualité " + loan.name() + " · " + propertyNames.getOrDefault(loan.propertyId(), ""), Category.CREDIT,
                        installments, Frequency.MONTHLY, Origin.REAL_ESTATE, "/pret/" + loan.id()), scope.loan(loan)));
            }
        }
        for (Property property : data.properties()) {
            if (month.isBefore(YearMonth.from(property.purchaseDate()))) continue;
            BigDecimal weight = scope.weight(Shares.Subject.PROPERTY, property.id());
            List<Expense> charges = data.charges().getOrDefault(property.id(), List.of());
            // Taxe fonciere : toute au membre qui la paie s'il est choisi (compte joint...), sinon comme le bien
            Long payer = data.taxPayers().get(property.id());
            Line tax = propertyTax(property, charges, data.monthlyTax().contains(property.id()), month);
            if (tax != null && payer != null && scope.person() == null) {
                tax = new Line(tax.label() + " · " + data.name(payer), tax.category(), tax.amount(), tax.frequency(), tax.origin(), tax.link());
            }
            if (tax != null) add(expenses, scope.apply(tax, payer == null ? weight : scope.member(payer)));
            if (weight.signum() == 0) continue;
            for (Line line : otherCharges(property, charges, month)) add(expenses, scope.apply(line, weight));
            for (RentPeriod period : data.rents().getOrDefault(property.id(), List.of())) {
                boolean active = period.kind() == Occupancy.RENTAL && !period.start().isAfter(month.atEndOfMonth())
                        && (period.end() == null || !period.end().isBefore(month.atDay(1)));
                if (active) {
                    String label = "Loyer perçu · " + property.name() + (weight.compareTo(BigDecimal.ONE) == 0 ? "" : " (" + Shares.percent(weight) + ")");
                    incomes.add(new IncomeLine(label, "Immobilier", period.monthly().multiply(weight).setScale(2, RoundingMode.HALF_UP), Frequency.MONTHLY,
                            Origin.REAL_ESTATE, "/administration/bien/" + property.id() + "/loyers"));
                }
            }
        }
        return new Month(month, incomes, expenses);
    }

    private static void add(List<Line> lines, Line line) {
        if (line != null) lines.add(line);
    }

    /** Taxe fonciere du mois : en octobre, ou un dixieme de janvier a octobre si mensualisee ; {@code null} sinon. */
    static Line propertyTax(Property property, List<Expense> charges, boolean monthly, YearMonth month) {
        BigDecimal propertyTax = ExpenseMath.amountFor(charges, ExpenseKind.PROPERTY_TAX, month.getYear());
        if (propertyTax == null) return null;
        String link = "/administration/bien/" + property.id() + "/charges";
        int m = month.getMonthValue();
        if (monthly && m <= 10) {
            // Mensualisee : un dixieme de janvier a septembre, le reste en octobre (le total de l'annee reste exact)
            BigDecimal tenth = propertyTax.divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP);
            BigDecimal amount = m < 10 ? tenth : propertyTax.subtract(tenth.multiply(BigDecimal.valueOf(9)));
            return new Line("Taxe foncière · " + property.name(), Category.TAXES, amount, Frequency.MONTHLY, Origin.REAL_ESTATE, link);
        }
        if (!monthly && m == 10) return new Line("Taxe foncière · " + property.name(), Category.TAXES, propertyTax, Frequency.ANNUAL, Origin.REAL_ESTATE, link);
        return null;
    }

    /** Autres charges du mois : taxe d'habitation, copropriete, assurance, autres. */
    private static List<Line> otherCharges(Property property, List<Expense> charges, YearMonth month) {
        List<Line> lines = new ArrayList<>();
        String link = "/administration/bien/" + property.id() + "/charges";
        int year = month.getYear();
        int m = month.getMonthValue();
        BigDecimal housingTax = ExpenseMath.amountFor(charges, ExpenseKind.HOUSING_TAX, year);
        if (housingTax != null && m == 11) lines.add(new Line("Taxe d'habitation · " + property.name(), Category.TAXES, housingTax, Frequency.ANNUAL, Origin.REAL_ESTATE, link));
        BigDecimal condo = ExpenseMath.amountFor(charges, ExpenseKind.CONDO_FEES, year);
        if (condo != null && m % 3 == 1) {
            lines.add(new Line("Charges de copropriété · " + property.name(), Category.HOUSING, quarter(condo), Frequency.QUARTERLY, Origin.REAL_ESTATE, link));
        }
        BigDecimal insurance = ExpenseMath.amountFor(charges, ExpenseKind.INSURANCE, year);
        if (insurance != null) lines.add(new Line("Assurance · " + property.name(), Category.INSURANCE, twelfth(insurance), Frequency.MONTHLY, Origin.REAL_ESTATE, link));
        BigDecimal other = ExpenseMath.amountFor(charges, ExpenseKind.OTHER, year);
        if (other != null) lines.add(new Line("Autres charges · " + property.name(), Category.HOUSING, twelfth(other), Frequency.MONTHLY, Origin.REAL_ESTATE, link));
        return lines;
    }

    private static BigDecimal quarter(BigDecimal annual) {
        return annual.divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal twelfth(BigDecimal annual) {
        return annual.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    // ---- Indicateurs ----

    /** Mensualites moyennes de credit sur les mois donnes. */
    private static BigDecimal credits(List<Month> months) {
        return months.stream().flatMap(month -> month.expenses().stream()).filter(line -> line.category() == Category.CREDIT)
                .map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(months.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Credits payes par les comptes joints, a la charge de chaque personne pour moitie (ou a parts egales entre
     * les personnes) : les banques comptent la mensualite, pas le virement qui l'alimente. Zero pour le foyer et
     * pour un compte.
     */
    private static BigDecimal jointCredits(Data data, Person person, LocalDate today) {
        if (person == null || person.isAccount()) return BigDecimal.ZERO;
        long persons = data.people().stream().filter(member -> !member.isAccount()).count();
        BigDecimal total = BigDecimal.ZERO;
        for (Person account : data.people()) {
            if (!account.isAccount()) continue;
            Scope scope = new Scope(account, null, Set.of(), data);
            List<Month> months = new ArrayList<>();
            for (int i = 0; i < HORIZON; i++) months.add(month(YearMonth.from(today).plusMonths(i), data, scope));
            total = total.add(credits(months));
        }
        return total.divide(BigDecimal.valueOf(persons), 2, RoundingMode.HALF_UP);
    }

    private static DebtRatio debtRatio(List<Month> months, Data data, Scope scope, LocalDate today, BigDecimal jointCredits) {
        if (scope.person() != null && scope.person().isAccount()) return new DebtRatio(credits(months), BigDecimal.ZERO, null);
        BigDecimal credits = credits(months).add(jointCredits);
        BigDecimal income = data.incomes().stream().filter(item -> scope.includes(item) && item.frequency().isRecurring() && item.activeOn(today))
                .map(item -> item.frequency().monthly(item.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rents = data.rents().entrySet().stream()
                .map(entry -> RentMath.currentMonthly(entry.getValue(), today).multiply(scope.weight(Shares.Subject.PROPERTY, entry.getKey())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        income = income.add(rents.multiply(RENT_SHARE)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal percent = income.signum() == 0 ? null : credits.multiply(BigDecimal.valueOf(100)).divide(income, 1, RoundingMode.HALF_UP);
        return new DebtRatio(credits, income, percent);
    }

    static List<Alert> alerts(List<Month> months, DebtRatio debt) {
        List<Alert> alerts = new ArrayList<>();
        for (Month month : months.subList(0, Math.min(ALERT_MONTHS, months.size()))) {
            List<Line> big = month.expenses().stream().filter(line -> line.frequency() != Frequency.MONTHLY && line.amount().compareTo(ALERT_THRESHOLD) >= 0).toList();
            if (big.isEmpty()) continue;
            BigDecimal total = big.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            alerts.add(new Alert(Format.month(month.month()) + " : " + big.stream().map(line -> line.label() + " " + Format.euroWhole(line.amount()))
                    .collect(Collectors.joining(", ")) + (big.size() > 1 ? " — " + Format.euroWhole(total) + " au total" : "") + ", en plus des dépenses habituelles.", false));
        }
        List<Month> deficits = months.stream().filter(month -> month.balance().signum() < 0 && !month.incomes().isEmpty()).toList();
        if (deficits.size() <= 2) {
            for (Month month : deficits) {
                alerts.add(new Alert(Format.month(month.month()) + " : il manque " + Format.euroWhole(month.balance().negate())
                        + ", les dépenses dépassent les revenus.", true));
            }
        } else {
            // Au-dela de deux mois, une seule alerte : nombre de mois, premier mois et ecart du manque
            BigDecimal least = deficits.stream().map(month -> month.balance().negate()).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal most = deficits.stream().map(month -> month.balance().negate()).max(BigDecimal::compareTo).orElseThrow();
            String gap = least.compareTo(most) == 0 ? Format.euroWhole(most) : "de " + Format.euroWhole(least) + " à " + Format.euroWhole(most);
            alerts.add(new Alert(deficits.size() + " mois sur " + months.size() + " en déficit, dès " + Format.month(deficits.getFirst().month()).toLowerCase(java.util.Locale.FRENCH)
                    + " : il manque " + gap + " par mois.", true));
        }
        if (debt.level() == DebtRatio.Level.SERIOUS) {
            alerts.add(new Alert("Taux d'endettement de " + Format.percent(debt.percent()) + ", au-dessus du seuil de 35 % retenu par les banques.", true));
        } else if (debt.level() == DebtRatio.Level.WARNING) {
            alerts.add(new Alert("Taux d'endettement de " + Format.percent(debt.percent()) + ", proche du seuil de 35 % retenu par les banques.", false));
        }
        return alerts;
    }
}
