package fr.patria;

import com.sun.net.httpserver.HttpServer;
import fr.patria.auth.AuthController;
import fr.patria.budget.BudgetController;
import fr.patria.budget.BudgetRepository;
import fr.patria.budget.Forecast;
import fr.patria.contribution.ContributionController;
import fr.patria.contribution.ContributionRepository;
import fr.patria.expense.ExpenseController;
import fr.patria.expense.ExpenseRepository;
import fr.patria.household.HouseholdController;
import fr.patria.household.ShareRepository;
import fr.patria.household.HouseholdRepository;
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
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Sessions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Point d'entree : assemble les modules et demarre le serveur HTTP.
 * C'est le seul endroit ou les dependances sont creees ; ailleurs, elles sont recues par constructeur.
 */
public final class App implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(App.class.getName());
    private final HttpServer server;
    private final ExecutorService executor;
    private final Database database;

    private App(HttpServer server, ExecutorService executor, Database database) {
        this.server = server;
        this.executor = executor;
        this.database = database;
    }

    public static void main(String[] args) {
        App app = start(Config.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        LOG.info("Patria écoute sur le port " + app.port() + ".");
    }

    public static App start(Config config) {
        try {
            if (config.databasePath().toAbsolutePath().getParent() != null) Files.createDirectories(config.databasePath().toAbsolutePath().getParent());
            Database database = Database.open(config.databasePath());
            Migrations.apply(database, config.clock());

            PropertyRepository properties = new PropertyRepository(database);
            LoanRepository loans = new LoanRepository(database);
            ContributionRepository contributions = new ContributionRepository(database);
            ExpenseRepository expenses = new ExpenseRepository(database);
            RentRepository rents = new RentRepository(database);
            HouseholdRepository household = new HouseholdRepository(database);
            BudgetRepository budgetItems = new BudgetRepository(database);
            ShareRepository shares = new ShareRepository(database);

            Sessions sessions = new Sessions(config.clock());
            Router router = new Router(sessions);
            router.publicGet("/sante", request -> new Response.Text(200, "ok"));
            new AuthController(sessions, config.password(), config.clock()).register(router);
            MarketService market = new MarketService(database, config.clock());
            new PortfolioController(new Portfolio(new Portfolio.Stores(properties, loans, contributions, expenses, rents), market, config.clock()), properties).register(router);
            new MarketController(market, properties).register(router);
            new PropertyController(properties, config.clock()).register(router);
            new LoanController(loans, properties, config.clock()).register(router);
            new ContributionController(contributions, properties, config.clock()).register(router);
            new ExpenseController(expenses, properties, config.clock()).register(router);
            new HouseholdController(household, config.clock()).register(router);
            Forecast.Sources budgetSources = new Forecast.Sources(household, budgetItems, properties, loans, expenses, rents, shares);
            new BudgetController(new Forecast(budgetSources, config.clock()), budgetSources, config.clock()).register(router);
            new RentController(rents, properties, config.clock()).register(router);

            HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            server.createContext("/", router);
            server.setExecutor(executor);
            server.start();
            return new App(server, executor, database);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(1);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        database.close();
    }
}
