package fr.patria;

import com.sun.net.httpserver.HttpServer;
import fr.patria.account.AccountRepository;
import fr.patria.auth.AuthController;
import fr.patria.storage.Database;
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Sessions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Point d'entree : assemble les modules et demarre le serveur HTTP.
 * C'est le seul endroit ou les dependances sont creees ; ailleurs, elles sont recues par constructeur.
 *
 * <p>Chaque foyer a sa base ({@link HouseholdSite}, ouverte a la premiere visite) ; les foyers, utilisateurs et
 * droits sont dans la base des comptes, {@code comptes.sqlite3}, a cote de DATABASE_PATH.
 */
public final class App implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(App.class.getName());
    static final String ACCOUNTS_FILE = "comptes.sqlite3";
    private final HttpServer server;
    private final ExecutorService executor;
    private final Database accountsDatabase;
    private final HouseholdSites sites;

    private final fr.patria.account.IpLocator locator;

    private App(HttpServer server, ExecutorService executor, Database accountsDatabase, HouseholdSites sites, fr.patria.account.IpLocator locator) {
        this.server = server;
        this.executor = executor;
        this.accountsDatabase = accountsDatabase;
        this.sites = sites;
        this.locator = locator;
    }

    public static void main(String[] args) {
        App app = start(Config.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        LOG.info("Patria écoute sur le port " + app.port() + ".");
    }

    public static App start(Config config) {
        try {
            if (config.databasePath().toAbsolutePath().getParent() != null) Files.createDirectories(config.databasePath().toAbsolutePath().getParent());
            if (config.encryptionKey() != null) {
                int encrypted = fr.patria.storage.Encryption.encryptLeftovers(config.databasePath().toAbsolutePath().getParent(), config.encryptionKey());
                if (encrypted > 0) LOG.info(encrypted + " sauvegarde(s) ou base(s) archivée(s) chiffrée(s).");
            }
            Database accountsDatabase = Database.open(config.databasePath().toAbsolutePath().resolveSibling(ACCOUNTS_FILE), config.encryptionKey());
            AccountRepository accounts = new AccountRepository(accountsDatabase);
            fr.patria.account.SecurityLog log = new fr.patria.account.SecurityLog(accountsDatabase);
            fr.patria.account.TwoFactorStore twoFactors = new fr.patria.account.TwoFactorStore(accountsDatabase);
            accounts.ensurePrimary("Mon foyer", LocalDate.now(config.clock()));
            if (config.resetAdminTwoFactor() && twoFactors.twoFactor(null).enabled()) {
                twoFactors.disableTwoFactor(null, null);
                log.audit(LocalDateTime.now(config.clock()), "Configuration", "TWO_FACTOR", null, null,
                        "Double authentification de l'administrateur retirée par ADMIN_2FA_RESET.");
                LOG.warning("Double authentification de l'administrateur retirée (ADMIN_2FA_RESET) : retirez cette variable de la configuration.");
            }
            HouseholdSites sites = new HouseholdSites(config, accounts);
            accounts.retireManagers(household -> sites.members(household).stream().map(fr.patria.account.HouseholdDirectory.Member::id).toList());
            // Avec une cle, toutes les bases sont ouvertes (donc chiffrees) des le demarrage, pas a la premiere visite.
            if (config.encryptionKey() != null) accounts.households().forEach(household -> sites.site(household.id()));
            else sites.site(accounts.households().getFirst().id());

            fr.patria.ui.BuildInfo.useSupportUrl(config.support());
            fr.patria.pdf.Stirling.configure(config.stirlingUrl(), config.stirlingApiKey());
            fr.patria.ui.Components.useRedactionTool(config.stirlingUrl() != null);
            Sessions sessions = new Sessions(config.clock());
            fr.patria.account.IpLocator locator = new fr.patria.account.IpLocator(log, config.offline(), config.clock());
            fr.patria.account.SessionViewers viewers = new fr.patria.account.SessionViewers(accounts, log, sites);
            Router router = new Router(sessions, viewers::of, household -> sites.site(household).router);
            router.publicGet("/sante", request -> new Response.Text(200, "ok"));
            router.publicGet("/aide/chiffrement", request -> Response.page(fr.patria.ui.SecurityPages.encryptionHelp(config.encryptionKey() != null, request.csrf())));
            router.publicGet("/confidentialite", request -> Response.page(fr.patria.ui.PrivacyPage.render(config.offline(), config.encryptionKey() != null, request.csrf())));
            AuthController auth = new AuthController(sessions, config.adminLogin(), config.password(),
                    new LoginAccounts(accounts, log, twoFactors, locator, config.clock()), config.clock());
            auth.register(router);
            fr.patria.account.AccountSupport.Stores stores = new fr.patria.account.AccountSupport.Stores(accounts, log, twoFactors, sites, sessions, config.adminLogin(),
                    locator, config.clock());
            new fr.patria.account.HouseholdsController(stores).register(router);
            new fr.patria.account.UsersController(stores).register(router);
            new fr.patria.account.SignupController(stores).register(router);
            new fr.patria.account.MyAccountController(stores).register(router);
            Path dataDirectory = config.databasePath().toAbsolutePath().getParent();
            new fr.patria.account.SecurityController(accounts, log, twoFactors, sessions, locator, new fr.patria.account.SecurityController.Installation(
                    config.encryptionKey() != null, config.offline(), auth::blocked, sites::backups), config.clock()).register(router);
            new fr.patria.account.WelcomeController(accounts, config.encryptionKey() != null, config.clock()).register(router);
            PropertyMove.register(router, sites, accounts, sessions);

            HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            server.createContext("/", router);
            server.setExecutor(executor);
            server.start();
            return new App(server, executor, accountsDatabase, sites, locator);
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
        locator.close();
        sites.closeAll();
        accountsDatabase.close();
    }
}
