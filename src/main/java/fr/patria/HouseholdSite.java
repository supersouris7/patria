package fr.patria;

import fr.patria.budget.BudgetController;
import fr.patria.budget.BudgetRepository;
import fr.patria.budget.Forecast;
import fr.patria.contribution.ContributionController;
import fr.patria.contribution.ContributionRepository;
import fr.patria.expense.ExpenseController;
import fr.patria.expense.ExpenseRepository;
import fr.patria.health.HealthController;
import fr.patria.health.HealthRepository;
import fr.patria.household.HouseholdController;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.ShareRepository;
import fr.patria.household.TransferController;
import fr.patria.loan.LoanController;
import fr.patria.loan.LoanRepository;
import fr.patria.loan.Renegotiation;
import fr.patria.market.HousingRates;
import fr.patria.market.MarketController;
import fr.patria.market.MarketService;
import fr.patria.portfolio.Portfolio;
import fr.patria.portfolio.PortfolioController;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.rent.RentController;
import fr.patria.rent.RentRepository;
import fr.patria.storage.Database;
import fr.patria.storage.Migrations;
import fr.patria.web.Router;
import fr.patria.wizard.WizardController;
import fr.patria.wizard.WizardRepository;
import java.nio.file.Path;
import java.time.Clock;

/** Un foyer : sa base SQLite, ses depots et ses pages (budget, immobilier, administration du foyer). */
final class HouseholdSite implements AutoCloseable {
    final Database database;
    final HouseholdRepository household;
    final PropertyRepository properties;
    final WizardRepository wizard;
    final HealthRepository healthPreferences;
    final Router router = new Router();

    private HouseholdSite(Database database, Clock clock, boolean offline, HousingRates rates, HouseholdController.Logins logins) {
        this.database = database;
        properties = new PropertyRepository(database);
        LoanRepository loans = new LoanRepository(database);
        ContributionRepository contributions = new ContributionRepository(database);
        ExpenseRepository expenses = new ExpenseRepository(database);
        RentRepository rents = new RentRepository(database);
        household = new HouseholdRepository(database);
        BudgetRepository budgetItems = new BudgetRepository(database);
        ShareRepository shares = new ShareRepository(database);

        MarketService market = new MarketService(database, clock, offline);
        Portfolio portfolio = new Portfolio(new Portfolio.Stores(properties, loans, contributions, expenses, rents), market, clock);
        new PortfolioController(portfolio, properties, rates).register(router);
        new MarketController(market, properties).register(router);
        new PropertyController(properties, clock).register(router);
        new LoanController(loans, properties, clock, () -> rates.current().map(rate -> new Renegotiation.Reference(rate.rate(), rate.month())).orElse(null))
                .register(router);
        new ContributionController(contributions, properties, clock).register(router);
        new ExpenseController(expenses, properties, clock).register(router);
        new HouseholdController(household, clock, logins).register(router);
        new TransferController(household, clock).register(router);
        Forecast.Sources budgetSources = new Forecast.Sources(household, budgetItems, properties, loans, expenses, rents, shares);
        Forecast forecast = new Forecast(budgetSources, clock);
        BudgetController budget = new BudgetController(forecast, budgetSources, clock);
        new RentController(rents, properties, clock).register(router);
        wizard = new WizardRepository(database);
        WizardController guide = new WizardController(new WizardController.Stores(household, budgetItems, shares, properties, loans, expenses, rents, wizard), clock);
        guide.register(router);
        // Previsionnel : rappel de la revue annuelle (le budget ne connait pas le guide, l'assemblage les relie).
        healthPreferences = new HealthRepository(database);
        HealthController health = new HealthController(forecast, household, portfolio, healthPreferences,
                () -> rates.current().map(rate -> new Renegotiation.Reference(rate.rate(), rate.month())).orElse(null), clock);
        health.register(router);
        budget.withHomeExtras(request -> fr.patria.ui.Html.concat(health.summary(request), guide.reminder(request), guide.progress(request))).register(router);
    }

    /** Ouvre (ou cree) la base du foyer et la met au dernier schema. */
    static HouseholdSite open(Path file, String key, Clock clock, boolean offline, HousingRates rates, HouseholdController.Logins logins) {
        Database database = Database.open(file, key);
        try {
            Migrations.apply(database, clock);
            return new HouseholdSite(database, clock, offline, rates, logins);
        } catch (RuntimeException exception) {
            database.close();
            throw exception;
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
