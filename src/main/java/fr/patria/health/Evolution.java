// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.budget.Forecast;
import fr.patria.budget.HistoryRepository;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Income;
import fr.patria.household.Visibility;
import fr.patria.loan.LoanRepository;
import fr.patria.loan.ScheduleLine;
import fr.patria.portfolio.Portfolio;
import fr.patria.property.Property;
import fr.patria.property.PropertyRepository;
import fr.patria.savings.Savings;
import fr.patria.savings.SavingsController;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.web.Request;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Historique et section « Votre evolution » du Bilan : un releve par mois (totaux du foyer, enregistre a la premiere
 * visite du mois ; le passe est reconstitue une fois a partir des lignes datees, des soldes d'epargne, des
 * echeanciers et des estimations), le pouvoir d'achat (revenus en euros courants et en euros d'aujourd'hui, avec
 * l'indice des prix INSEE garde sur place), le patrimoine net, le reste a vivre, le prevu face au reel, le score et le
 * revenu fiscal de reference par annee.
 */
final class Evolution {
    /** Profondeur de la reconstitution du passe. */
    private static final int REBUILD_MONTHS = 36;

    private final Forecast forecast;
    private final HouseholdRepository household;
    private final HistoryRepository history;
    private final SavingsController savings;
    private final Portfolio portfolio;
    private final PropertyRepository properties;
    private final LoanRepository loans;
    private final EconomiesController economies;
    private final Clock clock;

    Evolution(Forecast forecast, HouseholdRepository household, HistoryRepository history, SavingsController savings, Portfolio portfolio,
              PropertyRepository properties, LoanRepository loans, EconomiesController economies, Clock clock) {
        this.forecast = forecast;
        this.household = household;
        this.history = history;
        this.savings = savings;
        this.portfolio = portfolio;
        this.properties = properties;
        this.loans = loans;
        this.economies = economies;
        this.clock = clock;
    }

    /** Le releve du foyer n'est ecrit que par quelqu'un qui le voit en entier (totaux, tous les membres). */
    private boolean seesAll(Request request) {
        return Visibility.of(request.viewer(), household.people()).seesEverything();
    }

    /** Releve du mois en cours (et, la premiere fois, reconstitution du passe). Sans effet pour une vue partielle. */
    void record(Request request) {
        if (!seesAll(request)) return;
        YearMonth now = YearMonth.now(clock);
        SortedMap<YearMonth, HistoryRepository.Snapshot> known = history.snapshots();
        if (known.containsKey(now) && !known.get(now).rebuilt()) return;
        SavingsController.Snapshot saved = savings.snapshot(request, java.util.Set.of());
        saved.inflation().ifPresent(inflation -> history.savePriceIndex(inflation.index()));
        if (known.isEmpty()) rebuild(saved, now);
        Forecast.Month month = forecast.months(now, now).getFirst();
        Portfolio.Overview estate = request.viewer().realEstate() ? portfolio.overview() : null;
        history.save(new HistoryRepository.Snapshot(now, month.income(), month.spending(), sum(saved.overview(), false), sum(saved.overview(), true),
                estate == null ? BigDecimal.ZERO : estate.value(), estate == null ? BigDecimal.ZERO : estate.debt(), economies.scoreOf(request), false));
    }

    private static BigDecimal sum(Savings.Overview overview, boolean stocks) {
        return overview.lines().stream().filter(line -> line.account().kind().stocks() == stocks).map(Savings.Line::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Mois passes reconstitues : revenus et depenses des lignes datees, soldes d'epargne releves chaque mois (le
     * dernier connu), valeur des biens (derniere estimation, sinon prix d'achat), capital restant du des echeanciers.
     * La bourse n'a pas d'historique fiable : ses especes seules sont comptees.
     */
    private void rebuild(SavingsController.Snapshot saved, YearMonth now) {
        YearMonth from = now.minusMonths(REBUILD_MONTHS);
        List<Forecast.Month> months = forecast.months(from, now.minusMonths(1));
        Map<Long, SortedMap<YearMonth, BigDecimal>> balances = savings.repository().history();
        Map<Long, Boolean> stockAccounts = new java.util.HashMap<>();
        saved.overview().lines().forEach(line -> stockAccounts.put(line.account().id(), line.account().kind().stocks()));
        List<Property> owned = properties.all();
        Map<Long, List<PropertyRepository.Estimate>> estimates = new java.util.HashMap<>();
        owned.forEach(property -> estimates.put(property.id(), properties.estimates(property.id())));
        Map<Long, List<ScheduleLine>> schedules = loans.schedules(loans.all().stream().map(fr.patria.loan.Loan::id).toList());
        for (Forecast.Month month : months) {
            LocalDate end = month.month().atEndOfMonth();
            BigDecimal savingsTotal = BigDecimal.ZERO;
            BigDecimal stocksTotal = BigDecimal.ZERO;
            for (Map.Entry<Long, SortedMap<YearMonth, BigDecimal>> account : balances.entrySet()) {
                SortedMap<YearMonth, BigDecimal> head = account.getValue().headMap(month.month().plusMonths(1));
                if (head.isEmpty()) continue;
                if (stockAccounts.getOrDefault(account.getKey(), false)) stocksTotal = stocksTotal.add(head.get(head.lastKey()));
                else savingsTotal = savingsTotal.add(head.get(head.lastKey()));
            }
            BigDecimal estateTotal = BigDecimal.ZERO;
            for (Property property : owned) {
                if (property.purchaseDate().isAfter(end)) continue;
                BigDecimal value = property.purchase();
                for (PropertyRepository.Estimate estimate : estimates.get(property.id())) {
                    if (!estimate.on().isAfter(end) && estimate.low() != null) value = estimate.low();
                }
                estateTotal = estateTotal.add(value);
            }
            BigDecimal debts = BigDecimal.ZERO;
            for (List<ScheduleLine> lines : schedules.values()) {
                ScheduleLine last = null;
                for (ScheduleLine line : lines) if (!line.date().isAfter(end)) last = line;
                if (last != null && last.remainingPrincipal() != null) debts = debts.add(last.remainingPrincipal());
            }
            history.save(new HistoryRepository.Snapshot(month.month(), month.income(), month.spending(), savingsTotal, stocksTotal, estateTotal, debts, null, true));
        }
    }

    /** Section « Votre evolution » du Bilan. */
    Html section(Request request) {
        record(request);
        List<Html> cards = new ArrayList<>();
        cards.add(purchasingPower(request));
        if (seesAll(request)) {
            SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots = history.snapshots();
            if (snapshots.size() >= 2) {
                YearMonth first = snapshots.firstKey();
                cards.add(chartCard("Patrimoine net", "Ce que le foyer possède moins ce qu'il doit, à la fin de chaque mois.",
                        List.of(new TrendChart.Series("Patrimoine net", "mine", known(snapshots), false)), Format::euroWhole,
                        snapshots.values().stream().anyMatch(HistoryRepository.Snapshot::rebuilt)
                                ? "Mois avant " + Format.month(firstReal(snapshots)) + " reconstitués (bourse : espèces seules)." : null));
                cards.add(chartCard("Reste à vivre", "Revenus moins dépenses prévues, mois par mois.",
                        List.of(new TrendChart.Series("Reste à vivre", "mine", values(snapshots, HistoryRepository.Snapshot::balance), false)), Format::euroWhole, null));
                SortedMap<YearMonth, BigDecimal> actual = history.actualSpending();
                if (!actual.isEmpty()) {
                    SortedMap<YearMonth, BigDecimal> planned = new TreeMap<>();
                    actual.keySet().forEach(month -> {
                        if (snapshots.containsKey(month)) planned.put(month, snapshots.get(month).spending());
                    });
                    cards.add(chartCard("Dépenses : prévu et réel", "Le prévu vient du budget ; le réel, des relevés bancaires importés (totaux par mois).",
                            List.of(new TrendChart.Series("Prévu", "ref1", planned, true), new TrendChart.Series("Réel", "mine", actual, false)), Format::euroWhole,
                            "Un mois partiellement importé paraît plus bas : seules les opérations des relevés comptent."));
                }
                SortedMap<YearMonth, BigDecimal> scores = new TreeMap<>();
                snapshots.values().stream().filter(snapshot -> snapshot.score() != null).forEach(snapshot -> scores.put(snapshot.month(), BigDecimal.valueOf(snapshot.score())));
                if (scores.size() >= 2) {
                    cards.add(chartCard("Santé financière", "Votre santé financière sur 100 (page Économies), relevée chaque mois.",
                            List.of(new TrendChart.Series("Santé financière", "mine", scores, false)), value -> value.setScale(0, RoundingMode.HALF_UP) + " / 100", null));
                }
            }
        }
        List<fr.patria.savings.SavingsRepository.TaxIncome> taxes = savings.repository().taxIncomes();
        if (!taxes.isEmpty()) {
            cards.add(Components.card("Revenu fiscal de référence", taxes.size(), Html.EMPTY, Html.of("<ul class=\"plain-list\">%s</ul>",
                    taxes.reversed().stream().map(tax -> Html.of("<li>Avis %s : <b>%s</b> · %s part%s</li>", tax.year(), Format.euroWhole(tax.income()),
                            Format.number(tax.parts(), 2), tax.parts().compareTo(BigDecimal.ONE) > 0 ? "s" : "")).collect(Html.joining()))));
        }
        return Html.of("<section id=\"evolution\" class=\"evolution\"><h2 class=\"section-heading\">Votre évolution</h2>%s</section>", Html.concat(cards.toArray(Html[]::new)));
    }

    /** Patrimoine net des mois connus : un mois reconstitue sans aucun solde ni bien connu n'est pas trace (ce serait un faux zero). */
    private static SortedMap<YearMonth, BigDecimal> known(SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots) {
        SortedMap<YearMonth, BigDecimal> result = new TreeMap<>();
        snapshots.forEach((month, snapshot) -> {
            boolean empty = snapshot.savings().signum() == 0 && snapshot.stocks().signum() == 0 && snapshot.estate().signum() == 0 && snapshot.debts().signum() == 0;
            if (!(snapshot.rebuilt() && empty)) result.put(month, snapshot.net());
        });
        return result;
    }

    private static YearMonth firstReal(SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots) {
        return snapshots.values().stream().filter(snapshot -> !snapshot.rebuilt()).map(HistoryRepository.Snapshot::month).findFirst().orElse(snapshots.lastKey());
    }

    private static SortedMap<YearMonth, BigDecimal> values(SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots,
                                                           java.util.function.Function<HistoryRepository.Snapshot, BigDecimal> value) {
        SortedMap<YearMonth, BigDecimal> result = new TreeMap<>();
        snapshots.forEach((month, snapshot) -> result.put(month, value.apply(snapshot)));
        return result;
    }

    private static Html chartCard(String title, String explanation, List<TrendChart.Series> series, java.util.function.Function<BigDecimal, String> format, String note) {
        return Components.card(title, null, Html.EMPTY, Html.concat(Html.of("<p class=\"muted\">%s</p>", explanation), TrendChart.render(series, format),
                note == null ? Html.EMPTY : Html.of("<p class=\"muted small\">%s</p>", note)));
    }

    /**
     * Pouvoir d'achat des revenus visibles : revenu mensuel regulier (primes lissees sur l'annee) en euros courants et
     * en euros d'aujourd'hui (corrige de l'indice des prix), depuis le premier revenu saisi (10 ans au plus).
     */
    private Html purchasingPower(Request request) {
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        List<Income> incomes = household.incomes().stream().filter(income -> visibility.canSeeAny(income.personId()) && income.frequency().isRecurring()).toList();
        SortedMap<YearMonth, BigDecimal> index = history.priceIndex();
        YearMonth now = YearMonth.now(clock);
        if (incomes.isEmpty()) return Html.EMPTY;
        YearMonth start = incomes.stream().map(income -> YearMonth.from(income.start())).min(YearMonth::compareTo).orElse(now);
        if (start.isBefore(now.minusYears(10))) start = now.minusYears(10);
        if (start.isBefore(now.minusMonths(12))) {
            // Assez de recul : on part au plus tot du premier mois ou l'indice est connu.
            if (!index.isEmpty() && start.isBefore(index.firstKey())) start = index.firstKey();
        } else {
            return Components.card("Pouvoir d'achat", null, Html.EMPTY, Html.raw("""
                    <p class="muted">Il faut au moins un an d'historique de revenus. Quand un salaire change, modifiez-le « à partir du » mois du changement (Gérer → Revenus) :
                    l'ancien montant est gardé, et ce graphique compare vos revenus à la hausse des prix.</p>"""));
        }
        SortedMap<YearMonth, BigDecimal> nominal = new TreeMap<>();
        for (YearMonth month = start; !month.isAfter(now); month = month.plusMonths(1)) {
            LocalDate day = month.atDay(15);
            BigDecimal total = incomes.stream().filter(income -> !income.start().isAfter(day) && (income.end() == null || !income.end().isBefore(day)))
                    .map(income -> income.frequency().monthly(income.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            nominal.put(month, total.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal latestIndex = index.isEmpty() ? null : index.get(index.lastKey());
        SortedMap<YearMonth, BigDecimal> real = new TreeMap<>();
        if (latestIndex != null) {
            nominal.forEach((month, value) -> {
                SortedMap<YearMonth, BigDecimal> head = index.headMap(month.plusMonths(1));
                if (!head.isEmpty()) real.put(month, value.multiply(latestIndex).divide(head.get(head.lastKey()), 2, RoundingMode.HALF_UP));
            });
        }
        BigDecimal first = nominal.get(nominal.firstKey());
        BigDecimal last = nominal.get(nominal.lastKey());
        Html verdict = Html.EMPTY;
        if (first.signum() > 0 && !real.isEmpty()) {
            BigDecimal firstReal = real.get(real.firstKey());
            double incomeChange = 100 * (last.doubleValue() / first.doubleValue() - 1);
            double prices = 100 * (firstReal.doubleValue() / first.doubleValue() - 1);
            double power = 100 * (last.doubleValue() / firstReal.doubleValue() - 1);
            verdict = Html.of("""
                    <p class="compare-verdict">Depuis %s : revenu mensuel %s → %s (<b class="%s">%s</b>), prix %s. \
                    Pouvoir d'achat : <b class="%s">%s</b>.</p>""", Format.month(nominal.firstKey()).toLowerCase(java.util.Locale.FRENCH), Format.euroWhole(first), Format.euroWhole(last),
                    tone(incomeChange), percent(incomeChange), percent(prices), tone(power), percent(power));
        }
        List<TrendChart.Series> series = new ArrayList<>();
        series.add(new TrendChart.Series("En euros courants", "ref1", nominal, true));
        if (!real.isEmpty()) series.add(new TrendChart.Series("En euros d'aujourd'hui", "mine", real, false));
        return Components.card("Pouvoir d'achat", null, Html.EMPTY, Html.concat(verdict, TrendChart.render(series, Format::euroWhole),
                Html.raw("<p class=\"muted small\">« En euros d'aujourd'hui » : corrigés de la hausse des prix (INSEE), primes lissées sur l'année. "
                        + "Si cette courbe baisse, vos revenus achètent moins qu'avant.</p>")));
    }

    private static String tone(double value) {
        return value > 0.05 ? "positive" : value < -0.05 ? "negative" : "";
    }

    private static String percent(double value) {
        BigDecimal rounded = BigDecimal.valueOf(Math.abs(value)).setScale(1, RoundingMode.HALF_UP);
        return (rounded.signum() == 0 ? "" : value > 0 ? "+" : "−") + Format.number(rounded, 1) + " %";
    }
}
