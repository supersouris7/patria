package fr.patria.health;

import fr.patria.budget.BudgetScope;
import fr.patria.budget.Forecast;
import fr.patria.common.ValidationException;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Income;
import fr.patria.household.Visibility;
import fr.patria.loan.Renegotiation;
import fr.patria.portfolio.Portfolio;
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
    private final Supplier<Renegotiation.Reference> rates;
    private final Clock clock;

    public HealthController(Forecast forecast, HouseholdRepository household, Portfolio portfolio, HealthRepository preferences, Supplier<Renegotiation.Reference> rates, Clock clock) {
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
            return Response.page(HealthPages.page(report.owner(), report.checks(), report.ignored(), report.estate(), request.csrf()));
        });
        router.userGet("/bilan.pdf", request -> {
            Report report = report(request);
            LocalDate today = LocalDate.now(clock);
            return new Response.Download("bilan-" + today + ".pdf", "application/pdf", HealthPages.pdf(report.owner(), report.checks(), report.ignored(), today));
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
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        Forecast.View view = BudgetScope.view(forecast, visibility, null);
        List<Income> incomes = household.incomes().stream()
                .filter(income -> view.person() == null ? visibility.canSeeAny(income.personId()) : income.personId() == view.person().id()).toList();
        List<Forecast.Average> expenses = forecast.averageExpenses(view);
        List<Check> checks = new ArrayList<>(HealthChecks.finances(view, forecast.averageIncomes(view), expenses, incomes, today));
        boolean estate = request.viewer().manages() || request.viewer().realEstate();
        Portfolio.Overview overview = estate ? portfolio.overview() : null;
        if (overview != null) checks.addAll(HealthChecks.properties(overview, rates.get(), today));
        checks.addAll(HealthChecks.taxes(incomes, expenses, overview, today));
        String owner = view.person() == null ? "Budget du foyer" : "Budget de " + view.person().name();
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
}
