// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.BudgetRepository;
import fr.patria.budget.Forecast;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.ShareRepository;
import fr.patria.household.Shares;
import fr.patria.household.Visibility;
import fr.patria.loan.Loan;
import fr.patria.loan.LoanRepository;
import fr.patria.savings.SavingsController;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;

/**
 * Page « Economies » : score du foyer, defi du mois, contrats a renegocier avec leur gain, economies realisees,
 * objectifs d'epargne et badges. Sobre et prive : aucune comparaison avec d'autres foyers.
 */
public final class EconomiesController {
    /** Ce que la page montre, calcule une fois par requete. */
    record State(List<Contracts.Contract> contracts, List<Loan> loans, List<EconomiesRepository.Economy> done, List<EconomiesRepository.Goal> goals,
                 Map<Long, BigDecimal> balances, Map<Long, String> accounts, Score score, Challenge challenge, List<Badge> badges, boolean canEdit,
                 Progression.Level level, List<Progression.Streak> streaks, List<Progression.Mission> missions,
                 java.util.Optional<fr.patria.savings.Savings.PurchasingPower> power) {
        BigDecimal saved() {
            return done.stream().map(EconomiesRepository.Economy::monthly).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(BigDecimal.valueOf(12));
        }

        BigDecimal potential() {
            return contracts.stream().map(Contracts.Contract::yearlyGain).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Score sur 100 et niveau. */
    record Score(int value, String level, List<String> parts) {
    }

    /** Defi du mois : une seule chose concrete, et ou la faire. */
    record Challenge(String title, String detail, String href, String action, boolean done) {
    }

    /** Badge : gagne ou a gagner, avec ce qu'il faut pour l'obtenir. */
    record Badge(String icon, String name, String how, boolean earned) {
    }

    private final BudgetRepository items;
    private final ShareRepository shares;
    private final HouseholdRepository household;
    private final EconomiesRepository economies;
    private final LoanRepository loans;
    private final HealthController health;
    private final SavingsController savings;
    private final Forecast forecast;
    private final Clock clock;
    /** Historique du foyer (series, mois importes) ; branche par {@link #useHistory}. */
    private fr.patria.budget.HistoryRepository history;

    public EconomiesController(BudgetRepository items, ShareRepository shares, HouseholdRepository household, EconomiesRepository economies, LoanRepository loans,
                               HealthController health, SavingsController savings, Forecast forecast, Clock clock) {
        this.items = items;
        this.shares = shares;
        this.household = household;
        this.economies = economies;
        this.loans = loans;
        this.health = health;
        this.savings = savings;
        this.forecast = forecast;
        this.clock = clock;
        // Les contrats ou un gain est possible remontent dans le Bilan, avec un lien ici.
        health.useEconomyChecks(request -> contracts(request).stream().filter(Contracts.Contract::above).map(contract -> Check.of("economy-" + contract.kind().name(),
                Check.Domain.FINANCES, Check.Level.INFO, contract.kind().label + " : jusqu'à " + fr.patria.ui.Format.euroWhole(contract.yearlyGain()) + " par an à gagner",
                contract.verdict() + " " + contract.benchmark()).link("/economies#contrat-" + contract.kind().name().toLowerCase(java.util.Locale.ROOT), "Voir la démarche")).toList());
    }

    /**
     * Historique et section « Votre evolution » du Bilan ; renvoie de quoi faire le releve du mois depuis le
     * Previsionnel (premiere visite du mois).
     */
    public java.util.function.Consumer<Request> useHistory(fr.patria.budget.HistoryRepository history, fr.patria.portfolio.Portfolio portfolio,
                                                           fr.patria.property.PropertyRepository properties) {
        this.history = history;
        Evolution evolution = new Evolution(forecast, household, history, savings, portfolio, properties, loans, this, clock);
        health.useEvolution(evolution::section);
        return evolution::record;
    }

    public void register(Router router) {
        router.userGet("/economies", request -> Response.page(EconomiesPages.page(state(request), request.confirmation(), request.csrf())));
        router.userPost("/economies/renegocie", this::renegotiated);
        router.userPost("/economies/{id}/supprimer", request -> {
            requireEdit(request);
            economies.deleteEconomy(request.id());
            return Response.redirect("/economies?ok=supprime#realisees");
        });
        router.userPost("/economies/objectifs", this::addGoal);
        router.userPost("/economies/objectifs/{id}/supprimer", request -> {
            requireEdit(request);
            economies.deleteGoal(request.id());
            return Response.redirect("/economies?ok=supprime#objectifs");
        });
    }

    /** Depenses visibles de la personne (memes droits que le budget). */
    private List<BudgetItem> visibleItems(Request request) {
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        Map<Shares.Key, Map<Long, Integer>> all = shares.all();
        return items.all().stream().filter(item -> visibility.seesEverything()
                || visibility.canSee(all.getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of()))).toList();
    }

    List<Contracts.Contract> contracts(Request request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate birth = request.viewer().signals().birthDate();
        return Contracts.find(visibleItems(request), today, birth == null ? null : Period.between(birth, today).getYears());
    }

    private boolean canEdit(Request request) {
        return request.viewer().wholeHousehold() || !request.viewer().members().isEmpty();
    }

    private void requireEdit(Request request) {
        if (!canEdit(request)) throw new ValidationException("Votre accès à ce foyer est en lecture seule.");
    }

    /** Niveau recent par compte (l'en-tete le demande a chaque page) : recalcule au plus toutes les 30 secondes. */
    private final Map<Long, Object[]> levels = new java.util.concurrent.ConcurrentHashMap<>();

    /** Niveau pour la pastille de l'en-tete (JSON) ; {@code null} pour l'administrateur. */
    public String levelJson(Request request) {
        Long user = request.viewer().userId();
        if (user == null) return "null";
        Object[] cached = levels.get(user);
        long now = clock.millis();
        if (cached == null || now - (long) cached[0] > 30_000) {
            Progression.Level level = state(request).level();
            cached = new Object[] {now, "{\"number\":" + level.number() + ",\"name\":\"" + level.name() + "\",\"xp\":" + level.xp() + ",\"percent\":" + level.percent() + "}"};
            levels.put(user, cached);
        }
        return (String) cached[1];
    }

    /** Score du foyer pour le releve mensuel. */
    int scoreOf(Request request) {
        return state(request).score().value();
    }

    State state(Request request) {
        LocalDate today = LocalDate.now(clock);
        List<Contracts.Contract> contracts = contracts(request).stream().sorted(java.util.Comparator.comparing(Contracts.Contract::yearlyGain).reversed()).toList();
        List<Loan> borrowed = request.viewer().realEstate() ? loans.all() : List.of();
        List<EconomiesRepository.Economy> done = economies.economies();
        List<EconomiesRepository.Goal> goals = economies.goals();
        SavingsController.Snapshot saved = savings.snapshot(request, java.util.Set.of());
        Map<Long, BigDecimal> balances = new java.util.HashMap<>();
        Map<Long, String> accounts = new java.util.LinkedHashMap<>();
        saved.overview().lines().forEach(line -> {
            balances.put(line.account().id(), line.value());
            accounts.put(line.account().id(), line.account().name());
        });
        HealthController.Report report = health.report(request);
        long bad = report.checks().stream().filter(check -> check.level() == Check.Level.BAD).count();
        long warn = report.checks().stream().filter(check -> check.level() == Check.Level.WARN).count();
        BigDecimal cover = saved.plan().coverMonths();
        Forecast.View view = forecast.next12Months();
        boolean hasIncome = view.hasIncome();
        boolean hasExpenses = view.hasExpenses();
        boolean noDeficit = hasIncome && view.months().stream().noneMatch(month -> month.balance().signum() < 0);
        Score score = score(bad, warn, hasIncome, hasExpenses, contracts.size(), cover);
        Challenge challenge = challenge(contracts, done, cover, noDeficit, today);
        BigDecimal savedMonthly = done.stream().map(EconomiesRepository.Economy::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean goalReached = goals.stream().anyMatch(goal -> goal.accountId() != null && balances.getOrDefault(goal.accountId(), BigDecimal.ZERO).compareTo(goal.target()) >= 0);
        List<Badge> badges = List.of(
                new Badge("📒", "Budget en place", "Des revenus et des dépenses saisis", hasIncome && hasExpenses),
                new Badge("🧭", "Sans mois difficile", "Aucun mois en déficit dans les 12 prochains", noDeficit),
                new Badge("🛟", "Précaution", "3 mois de dépenses de côté, disponibles", cover.compareTo(BigDecimal.valueOf(3)) >= 0),
                new Badge("✂️", "Première économie", "Un contrat renégocié", !done.isEmpty()),
                new Badge("💶", "100 € par mois", "100 € par mois d'économies réalisées", savedMonthly.compareTo(BigDecimal.valueOf(100)) >= 0),
                new Badge("🎯", "Objectif atteint", "Un objectif d'épargne rempli", goalReached),
                new Badge("🏅", "Santé excellente", "Santé financière de 85 ou plus", score.value() >= 85));
        // Progression : points, niveau, series et missions, recalcules a partir de l'etat (rien de stocke).
        boolean goalLinked = goals.stream().anyMatch(goal -> goal.accountId() != null);
        boolean goalHalf = goals.stream().anyMatch(goal -> goal.accountId() != null
                && balances.getOrDefault(goal.accountId(), BigDecimal.ZERO).multiply(BigDecimal.valueOf(2)).compareTo(goal.target()) >= 0);
        int reached = (int) goals.stream().filter(goal -> goal.accountId() != null && balances.getOrDefault(goal.accountId(), BigDecimal.ZERO).compareTo(goal.target()) >= 0).count();
        Progression.Facts facts = new Progression.Facts(hasIncome && hasExpenses, (int) badges.stream().filter(Badge::earned).count(), done.size(), goals.size(), reached,
                goalLinked, goalHalf, contracts.size(), (int) contracts.stream().filter(Contracts.Contract::above).count(), cover, noDeficit, score.value(),
                history == null ? new java.util.TreeMap<>() : history.snapshots(), history == null ? new java.util.TreeMap<>() : history.actualSpending(),
                java.time.YearMonth.from(today));
        List<Progression.Mission> missions = Progression.missions(facts);
        return new State(contracts, borrowed, done, goals, balances, accounts, score, challenge, badges, canEdit(request),
                Progression.level(Progression.xp(facts, missions)), Progression.streaks(facts), missions, saved.power());
    }

    /**
     * Score sur 100 : 40 points pour le bilan (moins 12 par point a corriger, 4 par point a surveiller), 30 pour un
     * budget complet (revenus, depenses, contrats reconnus), 30 pour l'epargne de precaution (6 mois = tout).
     */
    static Score score(long bad, long warn, boolean hasIncome, boolean hasExpenses, int contracts, BigDecimal cover) {
        int healthPart = (int) Math.max(0, 40 - 12 * bad - 4 * warn);
        int complete = (hasIncome ? 10 : 0) + (hasExpenses ? 10 : 0) + Math.min(10, contracts * 2);
        int cushion = (int) Math.round(30 * Math.min(1, cover.doubleValue() / 6));
        int value = hasIncome || hasExpenses ? healthPart + complete + cushion : 0;
        // Qualificatifs propres a la sante financiere (les niveaux de progression ont leurs propres noms).
        String level = value >= 85 ? "Excellente" : value >= 65 ? "Solide" : value >= 40 ? "Correcte" : "À construire";
        return new Score(value, level, List.of("Bilan de santé : " + healthPart + " / 40", "Budget complet : " + complete + " / 30",
                "Épargne de précaution : " + cushion + " / 30"));
    }

    /** Une seule chose a faire ce mois-ci, la plus utile parmi ce qui reste. */
    static Challenge challenge(List<Contracts.Contract> contracts, List<EconomiesRepository.Economy> done, BigDecimal cover, boolean noDeficit, LocalDate today) {
        boolean renegotiatedThisMonth = done.stream().anyMatch(economy -> java.time.YearMonth.from(economy.doneOn()).equals(java.time.YearMonth.from(today)));
        Contracts.Contract best = contracts.stream().filter(Contracts.Contract::above).findFirst().orElse(null);
        if (best != null && !renegotiatedThisMonth) {
            return new Challenge("Renégociez votre " + best.kind().label.toLowerCase(java.util.Locale.FRENCH),
                    "Jusqu'à " + fr.patria.ui.Format.euroWhole(best.yearlyGain()) + " par an à gagner, en une démarche.",
                    "#contrat-" + best.kind().name().toLowerCase(java.util.Locale.ROOT), "Voir la démarche", false);
        }
        if (!noDeficit) return new Challenge("Évitez les mois en déficit", "Repérez le mois le plus serré du prévisionnel et lissez une grosse dépense.", "/", "Ouvrir le prévisionnel", false);
        if (cover.compareTo(BigDecimal.valueOf(3)) < 0) {
            return new Challenge("Mettez de côté 3 mois de dépenses", "Un versement programmé vers un livret fait le travail tout seul.", "/epargne#plan", "Ouvrir le plan d'épargne", false);
        }
        return new Challenge(renegotiatedThisMonth ? "Défi du mois réussi" : "Rien d'urgent ce mois-ci",
                renegotiatedThisMonth ? "Un contrat renégocié ce mois-ci : bravo." : "Vos contrats sont dans les repères et votre précaution est en place.", null, null, true);
    }

    /**
     * « J'ai renegocie » : le nouveau montant mensuel s'applique a la ligne de budget a partir du mois prochain (l'ancien
     * s'arrete la veille, meme repartition), et l'economie rejoint le compteur.
     */
    private Response renegotiated(Request request) {
        requireEdit(request);
        FormData form = request.form();
        try {
            BigDecimal after = form.positiveAmount("nouveau", "Nouveau montant par mois");
            String kindName = form.raw("contrat");
            LocalDate today = LocalDate.now(clock);
            LocalDate from = today.withDayOfMonth(1).plusMonths(1);
            if (kindName.startsWith("pret-")) {
                // Assurance de pret : l'economie seule (l'echeancier du pret n'est pas modifie ici).
                BigDecimal before = form.positiveAmount("ancien", "Ancien montant par mois");
                if (after.compareTo(before) >= 0) throw new ValidationException("Le nouveau montant doit être plus bas que l'ancien.");
                economies.addEconomy("LOAN_INSURANCE", "Assurance de prêt", before, after, today);
                return Response.redirect("/economies?ok=enregistre#realisees");
            }
            Contracts.Contract contract = contracts(request).stream().filter(candidate -> candidate.kind().name().equals(kindName)).findFirst()
                    .orElseThrow(() -> new NotFoundException("Contrat introuvable", "Ce contrat n'est plus dans vos dépenses."));
            if (after.compareTo(contract.monthly()) >= 0) throw new ValidationException("Le nouveau montant doit être plus bas que l'actuel (" + fr.patria.ui.Format.euro(contract.monthly()) + ").");
            if (contract.items().size() == 1) {
                BudgetItem item = contract.items().getFirst();
                Visibility visibility = Visibility.of(request.viewer(), household.people());
                Map<Long, Integer> split = shares.of(Shares.Subject.BUDGET_ITEM, item.id());
                visibility.requireEdit(visibility.canEdit(split));
                // Montant par versement equivalent au nouveau montant mensuel, dans la frequence de la ligne.
                BigDecimal perPayment = after.multiply(item.amount()).divide(item.frequency().monthly(item.amount()), 2, java.math.RoundingMode.HALF_UP);
                if (!from.isAfter(item.start())) {
                    items.update(new BudgetItem(item.id(), item.label(), item.category(), perPayment, item.frequency(), item.start(), item.end()));
                } else {
                    items.update(new BudgetItem(item.id(), item.label(), item.category(), item.amount(), item.frequency(), item.start(), from.minusDays(1)));
                    long id = items.insert(new BudgetItem(0, item.label(), item.category(), perPayment, item.frequency(), from, item.end()));
                    shares.set(Shares.Subject.BUDGET_ITEM, id, split);
                }
            }
            economies.addEconomy(contract.kind().name(), contract.kind().label, contract.monthly(), after, today);
            levels.clear();
            return Response.redirect("/economies?ok=enregistre#realisees");
        } catch (ValidationException exception) {
            return Response.invalid(EconomiesPages.page(state(request), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    /** Objectif d'epargne : nom, illustration, montant vise, compte suivi. */
    private Response addGoal(Request request) {
        requireEdit(request);
        FormData form = request.form();
        try {
            String name = form.requiredText("nom", "Nom de l'objectif", 60);
            String emoji = EconomiesPages.EMOJIS.contains(form.raw("emoji")) ? form.raw("emoji") : "🎯";
            BigDecimal target = form.positiveAmount("montant", "Montant visé");
            Long account = form.raw("compte").isBlank() ? null : Long.parseLong(form.raw("compte"));
            State state = state(request);
            if (account != null && !state.accounts().containsKey(account)) throw new ValidationException("Compte d'épargne inconnu.");
            if (state.goals().size() >= 12) throw new ValidationException("Douze objectifs au plus.");
            economies.addGoal(name, emoji, target, account, LocalDate.now(clock));
            return Response.redirect("/economies?ok=enregistre#objectifs");
        } catch (NumberFormatException exception) {
            return Response.invalid(EconomiesPages.page(state(request), Notice.error("Compte d'épargne inconnu."), request.csrf()));
        } catch (ValidationException exception) {
            return Response.invalid(EconomiesPages.page(state(request), Notice.error(exception.getMessage()), request.csrf()));
        }
    }
}
