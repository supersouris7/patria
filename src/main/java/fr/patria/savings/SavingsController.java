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

    public SavingsController(SavingsRepository savings, HouseholdRepository household, Forecast forecast, MarketFeeds feeds, Clock clock) {
        this.savings = savings;
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
                return Response.redirect("/epargne?ok=enregistre#compte-" + account.id());
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage())));
            }
        });
        router.userPost("/epargne/compte/{id}/supprimer", request -> {
            savings.deleteAccount(editable(request).id());
            return Response.redirect("/epargne?ok=supprime");
        });
        router.userPost("/epargne/compte/{id}/ligne", this::addPosition);
        router.userPost("/epargne/ligne/{id}/supprimer", request -> {
            SavingsRepository.Position position = savings.positions().stream().filter(candidate -> candidate.id() == request.id()).findFirst()
                    .orElseThrow(() -> new NotFoundException("Ligne introuvable", "Cette ligne n'existe pas."));
            editable(request, position.accountId());
            savings.deletePosition(position.id());
            return Response.redirect("/epargne?ok=supprime#compte-" + position.accountId());
        });
        router.userPost("/epargne/plan", request -> {
            String lep = request.form().raw("lep");
            savings.setPreference("lep", lep.equals("oui") || lep.equals("non") ? lep : "");
            int months = months(request.form().raw("mois"));
            savings.setPreference("months", String.valueOf(months));
            return Response.redirect("/epargne?ok=enregistre#plan");
        });
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
        BigDecimal spending = forecast.averageExpenses(view).stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add)
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
        return SavingsPages.page(snapshot(request, java.util.Set.of()), visibility.people(), feeds.offline(), LocalDate.now(clock), notice, request.csrf());
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
            return Response.redirect("/epargne?ok=enregistre#compte-" + id);
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
            return Response.redirect("/epargne?ok=enregistre#compte-" + account.id());
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
