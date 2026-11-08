package fr.patria.savings;

import fr.patria.budget.BudgetScope;
import fr.patria.budget.Forecast;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Person;
import fr.patria.household.Visibility;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Page Epargne : livrets, assurance-vie, PEA et comptes-titres de chaque membre, cours de bourse et inflation, plan
 * d'epargne de securite. Chaque compte appartient a un membre : il est visible et modifiable par ceux qui ont acces a
 * ce membre.
 */
public final class SavingsController {
    private final SavingsRepository savings;
    private final HouseholdRepository household;
    private final Forecast forecast;
    private final MarketFeeds feeds;
    private final Clock clock;
    private final fr.patria.budget.BudgetRepository budget;

    public SavingsController(SavingsRepository savings, HouseholdRepository household, Forecast forecast, fr.patria.budget.BudgetRepository budget,
                             MarketFeeds feeds, Clock clock) {
        this.savings = savings;
        this.budget = budget;
        this.household = household;
        this.forecast = forecast;
        this.feeds = feeds;
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/epargne", request -> Response.page(page(request, request.confirmation())));
        router.userPost("/epargne/compte", this::addAccount);
        router.userPost("/epargne/compte/{id}", request -> {
            SavingsRepository.Account account = editable(request);
            try {
                savings.updateBalance(account.id(), request.form().amount("balance", "Solde"), LocalDate.now(clock));
                return done(request, "enregistre", "compte-" + account.id());
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage())));
            }
        });
        router.userPost("/epargne/compte/{id}/versement", this::setContribution);
        router.userPost("/epargne/compte/{id}/supprimer", request -> {
            SavingsRepository.Account account = editable(request);
            if (account.contribution() != null && account.contribution().item() != null) budget.deleteWithShares(account.contribution().item());
            savings.deleteAccount(account.id());
            return done(request, "supprime", null);
        });
        router.userPost("/epargne/compte/{id}/ligne", this::addPosition);
        router.userPost("/epargne/compte/{id}/import", this::previewPositions);
        router.userPost("/epargne/compte/{id}/import/valider", this::importPositions);
        router.userPost("/epargne/ligne/{id}/supprimer", request -> {
            SavingsRepository.Position position = savings.positions().stream().filter(candidate -> candidate.id() == request.id()).findFirst()
                    .orElseThrow(() -> new NotFoundException("Ligne introuvable", "Cette ligne n'existe pas."));
            editable(request, position.accountId());
            savings.deletePosition(position.id());
            return done(request, "supprime", "compte-" + position.accountId());
        });
        router.userPost("/epargne/plan", request -> {
            String lep = request.form().raw("lep");
            savings.setPreference("lep", lep.equals("oui") || lep.equals("non") ? lep : "");
            int months = months(request.form().raw("mois"));
            savings.setPreference("months", String.valueOf(months));
            return Response.redirect("/epargne?ok=enregistre#plan");
        });
    }

    /** Apres une action : retour a l'etape du guide d'ou elle vient ({@code retour}), sinon a la page Epargne. */
    private static Response done(Request request, String ok, String anchor) {
        String back = back(request);
        return Response.redirect((back != null ? back : "/epargne") + "?ok=" + ok + (anchor == null ? "" : "#" + anchor));
    }

    static String back(Request request) {
        String back = fr.patria.web.LocalPath.safe(request.form().raw("retour"));
        return back != null && back.startsWith("/assistant/") && !back.contains("?") ? back : null;
    }

    /** Versement programme : le solde avance a chaque echeance, une ligne de budget (Virements) le reprend. */
    private Response setContribution(Request request) {
        SavingsRepository.Account account = editable(request);
        FormData form = request.form();
        try {
            LocalDate today = LocalDate.now(clock);
            // Le solde du jour (versements deja comptes) devient le nouveau point de depart.
            savings.updateBalance(account.id(), Savings.balance(account, today), today);
            if (account.contribution() != null && account.contribution().item() != null) budget.deleteWithShares(account.contribution().item());
            if (form.raw("supprimer").isBlank()) {
                BigDecimal amount = form.amount("montant", "Montant du versement");
                if (amount.signum() <= 0) throw new ValidationException("Montant du versement : plus de 0 €.");
                fr.patria.common.Frequency frequency = form.raw("frequence").isBlank() ? fr.patria.common.Frequency.MONTHLY : fr.patria.common.Frequency.parse(form.raw("frequence"));
                if (frequency == fr.patria.common.Frequency.ONCE) throw new ValidationException("Un versement programmé revient régulièrement : choisissez une fréquence.");
                LocalDate start = form.raw("debut").isBlank() ? today : form.date("debut", "Premier versement");
                long item = budget.insert(new fr.patria.budget.BudgetItem(0, "Versement " + account.name(), fr.patria.budget.Category.TRANSFER, amount, frequency, start, null),
                        fr.patria.household.Shares.only(account.personId(), household.people()));
                savings.setContribution(account.id(), new SavingsRepository.Contribution(amount, frequency, start, item));
            } else {
                savings.setContribution(account.id(), null);
            }
            return done(request, "enregistre", "compte-" + account.id());
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error(exception.getMessage())));
        }
    }

    /** Lignes de bourse collees (reponse de l'IA) : apercu avec une case par ligne. */
    private Response previewPositions(Request request) {
        SavingsRepository.Account account = editable(request);
        try {
            if (!account.kind().stocks()) throw new ValidationException("Les lignes de bourse s'importent sur un PEA ou un compte-titres.");
            String content = new String(request.form().uploadedText("csv", "Lignes"), java.nio.charset.StandardCharsets.UTF_8);
            List<PositionCsv.Row> rows = PositionCsv.parse(content, savings.positions().stream().filter(position -> position.accountId() == account.id()).toList(), feeds::resolve);
            return Response.page(SavingsPages.importPreview(account, rows, content, back(request), request.csrf()));
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error("Import impossible. " + exception.getMessage())));
        }
    }

    private Response importPositions(Request request) {
        SavingsRepository.Account account = editable(request);
        try {
            String content = new String(java.util.Base64.getUrlDecoder().decode(request.form().raw("contenu")), java.nio.charset.StandardCharsets.UTF_8);
            List<PositionCsv.Row> rows = PositionCsv.parse(content, savings.positions().stream().filter(position -> position.accountId() == account.id()).toList(), feeds::resolve);
            int added = 0;
            for (PositionCsv.Row row : rows) {
                if (!row.fresh() || !"on".equals(request.form().raw("importer_" + row.line()))) continue;
                savings.addPosition(new SavingsRepository.Position(0, account.id(), row.symbol(), row.label(), row.quantity(), row.cost(), null));
                added++;
            }
            if (added == 0) throw new ValidationException("Cochez au moins une ligne à importer.");
            return done(request, "importe", "compte-" + account.id());
        } catch (IllegalArgumentException exception) {
            return Response.invalid(page(request, Notice.error("Import impossible : contenu illisible, recommencez.")));
        }
    }

    /** Ce que voit la personne connectee : ses comptes, la vue, le plan (partage avec le bilan). */
    public record Snapshot(Savings.Overview overview, Savings.Plan plan, java.util.Optional<MarketFeeds.Inflation> inflation,
                           java.util.Optional<Savings.PurchasingPower> power, String lep, List<Person> holders) {
    }

    /** Epargne visible, plan et inflation ; {@code scope} non vide : seulement ces membres (perimetre du bilan). */
    public Snapshot snapshot(Request request, java.util.Set<Long> scope) {
        LocalDate today = LocalDate.now(clock);
        List<Person> people = household.people();
        Visibility visibility = Visibility.of(request.viewer(), people);
        java.util.function.LongPredicate in = person -> visibility.sees(person) && (scope.isEmpty() || scope.contains(person));
        List<SavingsRepository.Account> accounts = savings.accounts().stream().filter(account -> in.test(account.personId())).toList();
        Savings.Overview overview = Savings.overview(accounts, savings.positions(), Savings.owners(people), feeds::quote, today);
        Forecast.View view = scope.isEmpty() ? BudgetScope.view(forecast, visibility, null) : forecast.next12MonthsOf(scope);
        // Les versements vers l'epargne (Virements) ne sont pas des depenses a couvrir par la precaution.
        BigDecimal spending = forecast.averageExpenses(view).stream().filter(line -> line.category() != fr.patria.budget.Category.TRANSFER)
                .map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal margin = view.months().isEmpty() ? BigDecimal.ZERO : view.months().stream().map(Forecast.Month::balance).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(view.months().size()), 0, RoundingMode.HALF_UP);
        List<Person> holders = people.stream().filter(person -> !person.isAccount() && in.test(person.id())).toList();
        String lep = savings.preference("lep");
        Savings.Plan plan = Savings.plan(spending, margin, overview, holders.stream().map(Person::id).toList(), months(savings.preference("months")), lep, today);
        java.util.Optional<MarketFeeds.Inflation> inflation = feeds.inflation();
        java.util.Optional<Savings.PurchasingPower> power = Savings.purchasingPower(household.incomes().stream().filter(income -> in.test(income.personId())).toList(),
                today, inflation);
        return new Snapshot(overview, plan, inflation, power, lep, holders);
    }

    private fr.patria.ui.Html page(Request request, Notice notice) {
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        Snapshot snapshot = snapshot(request, java.util.Set.of());
        LocalDate today = LocalDate.now(clock);
        // Historique : le solde du mois en cours de chaque placement visible (graphique de l'epargne).
        snapshot.overview().lines().forEach(line -> savings.recordHistory(line.account().id(), java.time.YearMonth.from(today), line.value()));
        return SavingsPages.page(snapshot, visibility.people(), history(snapshot, today), feeds.offline(), today, notice, request.csrf());
    }

    /** 24 derniers mois, empiles : disponible, long terme, bourse (dernier solde connu reporte d'un mois sur l'autre). */
    List<SavingsPages.Bar> history(Snapshot snapshot, LocalDate today) {
        java.util.Map<Long, java.util.SortedMap<java.time.YearMonth, BigDecimal>> history = savings.history();
        List<SavingsPages.Bar> bars = new java.util.ArrayList<>();
        java.time.YearMonth current = java.time.YearMonth.from(today);
        for (int back = 23; back >= 0; back--) {
            java.time.YearMonth month = current.minusMonths(back);
            BigDecimal available = BigDecimal.ZERO;
            BigDecimal longTerm = BigDecimal.ZERO;
            BigDecimal stocks = BigDecimal.ZERO;
            for (Savings.Line line : snapshot.overview().lines()) {
                java.util.SortedMap<java.time.YearMonth, BigDecimal> known = history.getOrDefault(line.account().id(), new java.util.TreeMap<>()).headMap(month.plusMonths(1));
                if (known.isEmpty()) continue;
                BigDecimal value = known.get(known.lastKey());
                if (line.account().kind().stocks()) stocks = stocks.add(value);
                else if (line.account().kind().available()) available = available.add(value);
                else longTerm = longTerm.add(value);
            }
            bars.add(new SavingsPages.Bar(month, available, longTerm, stocks));
        }
        return bars;
    }

    /** Ce qu'affiche l'etape « Epargne et bourse » du guide : comptes, formulaires et import des lignes, avec retour au guide. */
    public fr.patria.ui.Html wizardPanel(Request request, String back) {
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        return SavingsPages.wizard(snapshot(request, java.util.Set.of()), visibility.people(), LocalDate.now(clock), back, request.csrf());
    }

    /** Nombre de placements et de lignes de bourse visibles (coches automatiques du guide). */
    public int[] counts(Request request) {
        java.util.List<SavingsRepository.Account> accounts = savings.accounts().stream().filter(account -> request.viewer().sees(account.personId())).toList();
        java.util.Set<Long> ids = accounts.stream().map(SavingsRepository.Account::id).collect(java.util.stream.Collectors.toSet());
        return new int[]{accounts.size(), (int) savings.positions().stream().filter(position -> ids.contains(position.accountId())).count()};
    }

    private Response addAccount(Request request) {
        FormData form = request.form();
        try {
            Visibility visibility = Visibility.of(request.viewer(), household.people());
            long person = Long.parseLong(form.raw("person").isBlank() ? "-1" : form.raw("person"));
            if (visibility.people().stream().noneMatch(candidate -> candidate.id() == person)) throw new ValidationException("Choisissez un membre.");
            SavingsKind kind = SavingsKind.parse(form.raw("kind"));
            String name = form.raw("name").isBlank() ? kind.label() : form.requiredText("name", "Nom", 60);
            BigDecimal rate = kind.regulated() || kind == SavingsKind.CURRENT ? null : optionalRate(form.raw("rate"));
            LocalDate opened = form.raw("opened_on").isBlank() ? null : form.date("opened_on", "Date d'ouverture");
            long id = savings.addAccount(new SavingsRepository.Account(0, person, kind, name, form.amount("balance", "Solde"), LocalDate.now(clock), rate, opened));
            BigDecimal monthly = form.optionalAmount("montant", "Versement mensuel");
            if (monthly != null && monthly.signum() > 0) {
                long item = budget.insert(new fr.patria.budget.BudgetItem(0, "Versement " + name, fr.patria.budget.Category.TRANSFER, monthly, fr.patria.common.Frequency.MONTHLY,
                        LocalDate.now(clock), null), fr.patria.household.Shares.only(person, household.people()));
                savings.setContribution(id, new SavingsRepository.Contribution(monthly, fr.patria.common.Frequency.MONTHLY, LocalDate.now(clock), item));
            }
            return done(request, "enregistre", "compte-" + id);
        } catch (NumberFormatException exception) {
            return Response.invalid(page(request, Notice.error("Choisissez un membre.")));
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error(exception.getMessage())));
        }
    }

    private Response addPosition(Request request) {
        SavingsRepository.Account account = editable(request);
        FormData form = request.form();
        try {
            if (!account.kind().stocks()) throw new ValidationException("Les lignes de bourse se saisissent sur un PEA ou un compte-titres.");
            String symbol = form.requiredText("symbol", "Code Yahoo Finance", 20).trim().toUpperCase(java.util.Locale.ROOT);
            if (!symbol.matches("[A-Z0-9.^=-]{1,20}")) throw new ValidationException("Code invalide : par exemple AI.PA pour Air Liquide à Paris.");
            String label = form.raw("label").isBlank() ? symbol : form.requiredText("label", "Nom", 60);
            BigDecimal quantity = quantity(form.raw("quantity"));
            savings.addPosition(new SavingsRepository.Position(0, account.id(), symbol, label, quantity, form.amount("cost", "Prix de revient total"),
                    form.optionalAmount("price", "Cours saisi")));
            return done(request, "enregistre", "compte-" + account.id());
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error(exception.getMessage())));
        }
    }

    private SavingsRepository.Account editable(Request request) {
        return editable(request, request.id());
    }

    /** Compte d'un membre accessible a la personne connectee (404 sinon, sans dire qu'il existe). */
    private SavingsRepository.Account editable(Request request, long id) {
        SavingsRepository.Account account = savings.account(id).orElseThrow(() -> new NotFoundException("Compte introuvable", "Ce compte n'existe pas."));
        if (!request.viewer().sees(account.personId())) throw new NotFoundException("Compte introuvable", "Ce compte n'existe pas.");
        return account;
    }

    static int months(String raw) {
        try {
            return Math.clamp(Integer.parseInt(raw.trim()), 1, 24);
        } catch (NumberFormatException exception) {
            return 6;
        }
    }

    private static BigDecimal optionalRate(String raw) {
        String value = raw.replace(',', '.').replace("%", "").trim();
        if (value.isEmpty()) return null;
        try {
            BigDecimal rate = new BigDecimal(value);
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(30)) > 0) throw new ValidationException("Taux invalide : entre 0 et 30 %.");
            return rate;
        } catch (NumberFormatException exception) {
            throw new ValidationException("Taux invalide : par exemple 2,5.");
        }
    }

    private static BigDecimal quantity(String raw) {
        try {
            BigDecimal quantity = new BigDecimal(raw.replace(',', '.').replaceAll("[\\s\\u00a0]", ""));
            if (quantity.signum() <= 0 || quantity.compareTo(BigDecimal.valueOf(10_000_000)) > 0) throw new NumberFormatException();
            return quantity.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw new ValidationException("Quantité invalide : par exemple 12 ou 3,5.");
        }
    }
}
