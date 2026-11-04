package fr.patria;

import fr.patria.budget.BudgetController;
import fr.patria.budget.BudgetRepository;
import fr.patria.budget.Forecast;
import fr.patria.contribution.ContributionController;
import fr.patria.contribution.ContributionRepository;
import fr.patria.expense.ExpenseController;
import fr.patria.expense.ExpenseRepository;
import fr.patria.household.HouseholdController;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.ShareRepository;
import fr.patria.household.TransferController;
import fr.patria.loan.LoanController;
import fr.patria.loan.LoanRepository;
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
import java.nio.file.Path;
import java.time.Clock;

/** Un foyer : sa base SQLite, ses depots et ses pages (budget, immobilier, administration du foyer). */
final class HouseholdSite implements AutoCloseable {
    final Database database;
    final HouseholdRepository household;
    final Router router = new Router();

    private HouseholdSite(Database database, Clock clock) {
        this.database = database;
        PropertyRepository properties = new PropertyRepository(database);
        LoanRepository loans = new LoanRepository(database);
        ContributionRepository contributions = new ContributionRepository(database);
        ExpenseRepository expenses = new ExpenseRepository(database);
        RentRepository rents = new RentRepository(database);
        household = new HouseholdRepository(database);
        BudgetRepository budgetItems = new BudgetRepository(database);
        ShareRepository shares = new ShareRepository(database);

        MarketService market = new MarketService(database, clock);
        new PortfolioController(new Portfolio(new Portfolio.Stores(properties, loans, contributions, expenses, rents), market, clock), properties).register(router);
        new MarketController(market, properties).register(router);
        new PropertyController(properties, clock).register(router);
        new LoanController(loans, properties, clock).register(router);
        new ContributionController(contributions, properties, clock).register(router);
        new ExpenseController(expenses, properties, clock).register(router);
        new HouseholdController(household, clock).register(router);
        new TransferController(household, clock).register(router);
        Forecast.Sources budgetSources = new Forecast.Sources(household, budgetItems, properties, loans, expenses, rents, shares);
        new BudgetController(new Forecast(budgetSources, clock), budgetSources, clock).register(router);
        new RentController(rents, properties, clock).register(router);
    }

    /** Ouvre (ou cree) la base du foyer et la met au dernier schema. */
    static HouseholdSite open(Path file, Clock clock) {
        Database database = Database.open(file);
        try {
            Migrations.apply(database, clock);
            return new HouseholdSite(database, clock);
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
