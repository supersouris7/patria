package fr.patria.health;

import fr.patria.budget.BudgetScope;
import fr.patria.budget.Forecast;
import fr.patria.common.ValidationException;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Income;
import fr.patria.household.Visibility;
import fr.patria.loan.Renegotiation;
import fr.patria.portfolio.Portfolio;
import fr.patria.savings.SavingsController;
import fr.patria.ui.Html;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bilan de sante : page, synthese PDF et resume du Previsionnel. Le gestionnaire a le bilan du foyer ; un utilisateur
 * celui de sa vue (et les biens seulement s'il consulte l'immobilier).
 */
public final class HealthController {
    private final Forecast forecast;
    private final HouseholdRepository household;
    private final Portfolio portfolio;
    private final HealthRepository preferences;
    private final fr.patria.household.HealthScopeRepository scopes;
    private final Supplier<Renegotiation.Reference> rates;
    private final Clock clock;
    private final SavingsController savings;

    public HealthController(Forecast forecast, HouseholdRepository household, Portfolio portfolio, HealthRepository preferences,
                            fr.patria.household.HealthScopeRepository scopes, SavingsController savings,
                            Supplier<Renegotiation.Reference> rates, Clock clock) {
        this.scopes = scopes;
        this.savings = savings;
        this.forecast = forecast;
        this.household = household;
        this.portfolio = portfolio;
        this.preferences = preferences;
        this.rates = rates;
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/bilan", request -> {
            Report report = report(request);
            return Response.page(HealthPages.page(report.owner(), report.checks(), report.ignored(), report.estate(), scopeForm(request), request.csrf()));
        });
        router.userGet("/bilan.pdf", request -> {
            Report report = report(request);
            LocalDate today = LocalDate.now(clock);
            return new Response.Download("bilan-" + today + ".pdf", "application/pdf", HealthPages.pdf(report.owner(), report.checks(), report.ignored(), today));
        });
        router.userPost("/bilan/comptes", request -> {
            saveScope(request);
            return Response.redirect("/bilan?ok=enregistre");
        });
        router.userGet("/patrimoine", request -> {
            SavingsController.Snapshot saved = savings.snapshot(request, Set.of());
            Portfolio.Overview estate = request.viewer().realEstate() ? portfolio.overview() : null;
            return Response.page(WealthPages.page(saved.overview(), estate, request.csrf()));
        });
        router.userPost("/bilan/ignorer", request -> change(request, true));
        router.userPost("/bilan/retablir", request -> change(request, false));
    }

    private Response change(Request request, boolean ignore) {
        String token = request.form().requiredText("check", "Avertissement", 140);
        Report report = report(request);
        List<Check> available = ignore ? report.checks() : report.ignored();
        if (available.stream().noneMatch(check -> check.canIgnore() && check.token().equals(token))) {
            throw new ValidationException("Cet avertissement n'est plus disponible dans votre bilan. Actualisez la page.");
        }
        if (ignore) preferences.ignore(request.viewer().userId(), token);
        else preferences.restore(request.viewer().userId(), token);
        return Response.redirect("/bilan" + (ignore ? "#ignores" : ""));
    }

    /** Points du bilan de la personne connectee et titre de la vue (« Budget du foyer »). */
    record Report(String owner, List<Check> checks, List<Check> ignored, boolean estate) {
    }

    Report report(Request request) {
        LocalDate today = LocalDate.now(clock);
        List<fr.patria.household.Person> people = household.people();
        Visibility visibility = Visibility.of(request.viewer(), people);
        // Perimetre du bilan : les comptes choisis (par defaut soi et les comptes joints visibles), sinon la vue du budget.
        Set<Long> scope = scopes.scope(request.viewer(), people);
        Forecast.View view = scope.isEmpty() ? BudgetScope.view(forecast, visibility, null) : forecast.next12MonthsOf(scope);
        List<Income> incomes = household.incomes().stream()
                .filter(income -> !scope.isEmpty() ? scope.contains(income.personId())
                        : view.person() == null ? visibility.canSeeAny(income.personId()) : income.personId() == view.person().id()).toList();
        List<Forecast.Average> expenses = forecast.averageExpenses(view);
        List<Check> checks = new ArrayList<>(HealthChecks.finances(view, forecast.averageIncomes(view), expenses, incomes, today));
        checks.addAll(HealthInsights.largeSums(view));
        checks.addAll(HealthInsights.tightestMonth(view));
        checks.addAll(HealthInsights.duplicates(expenses));
        for (fr.patria.household.Person account : people) {
            if (account.isAccount() && visibility.sees(account.id()) && (scope.isEmpty() || scope.contains(account.id()))) {
                checks.addAll(HealthInsights.jointAccount(account.name(), forecast.next12MonthsOf(Set.of(account.id()))));
            }
        }
        boolean estate = request.viewer().realEstate();
        Portfolio.Overview overview = estate ? portfolio.overview() : null;
        if (overview != null) checks.addAll(HealthChecks.properties(overview, rates.get(), today));
        checks.addAll(HealthChecks.taxes(incomes, expenses, overview, today));
        // Audit de l'epargne : remplace le simple repere de precaution quand des placements sont saisis.
        SavingsController.Snapshot saved = savings.snapshot(request, scope);
        List<Check> audit = HealthSavings.audit(saved, today);
        if (!saved.overview().isEmpty()) checks.removeIf(check -> check.id().equals("savings"));
        checks.addAll(audit);
        String owner = !scope.isEmpty() ? "Bilan de " + String.join(", ", people.stream().filter(person -> scope.contains(person.id())).map(fr.patria.household.Person::name).toList())
                : view.person() == null ? "Budget du foyer" : "Budget de " + view.person().name();
        Set<String> ignored = preferences.ignored(request.viewer().userId());
        return new Report(owner, checks.stream().filter(check -> !ignored.contains(check.token())).toList(),
                checks.stream().filter(check -> ignored.contains(check.token())).toList(), estate);
    }

    /** Encart du Previsionnel : nombre de points a corriger ou a surveiller, vide si tout va bien. */
    public Html summary(Request request) {
        List<Check> checks = report(request).checks();
        long bad = checks.stream().filter(check -> check.level() == Check.Level.BAD).count();
        long warn = checks.stream().filter(check -> check.level() == Check.Level.WARN).count();
        if (bad == 0 && warn == 0) return Html.EMPTY;
        return HealthPages.summary(bad, warn);
    }

    public long warningCount(Request request) {
        return report(request).checks().stream().filter(check -> check.level() == Check.Level.BAD || check.level() == Check.Level.WARN).count();
    }

    /** Comptes du bilan, choisis parmi les membres visibles (au moins un). */
    private void saveScope(Request request) {
        if (request.viewer().isAdministrator()) throw new ValidationException("L'administrateur n'a pas de bilan.");
        Set<Long> chosen = new java.util.LinkedHashSet<>();
        for (fr.patria.household.Person person : household.people()) {
            if (request.viewer().sees(person.id()) && "on".equals(request.form().raw("membre_" + person.id()))) chosen.add(person.id());
        }
        if (chosen.isEmpty()) throw new ValidationException("Cochez au moins un compte à prendre en compte dans le bilan.");
        scopes.save(request.viewer().userId(), chosen);
    }

    /** Formulaire « Comptes pris en compte » : membres visibles, cases cochees selon le perimetre actuel. */
    Html scopeForm(Request request) {
        if (request.viewer().isAdministrator()) return Html.EMPTY;
        List<fr.patria.household.Person> people = household.people();
        Set<Long> scope = scopes.scope(request.viewer(), people);
        List<fr.patria.household.Person> visible = people.stream().filter(person -> request.viewer().sees(person.id())).toList();
        return HealthPages.scope(visible, scope, scopes.configured(request.viewer().userId()), "/bilan/comptes", request.csrf());
    }
}
