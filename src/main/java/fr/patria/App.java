package fr.patria;

import com.sun.net.httpserver.HttpServer;
import fr.patria.account.AccountController;
import fr.patria.account.AccountRepository;
import fr.patria.account.Household;
import fr.patria.account.HouseholdDirectory;
import fr.patria.account.User;
import fr.patria.auth.AuthController;
import fr.patria.common.NotFoundException;
import fr.patria.storage.Database;
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Session;
import fr.patria.web.Sessions;
import fr.patria.web.Viewer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Sites sites;

    private App(HttpServer server, ExecutorService executor, Database accountsDatabase, Sites sites) {
        this.server = server;
        this.executor = executor;
        this.accountsDatabase = accountsDatabase;
        this.sites = sites;
    }

    /** Bases des foyers ouvertes, par identifiant de foyer. */
    private static final class Sites implements HouseholdDirectory {
        private final Config config;
        private final AccountRepository accounts;
        private final Map<Long, HouseholdSite> open = new ConcurrentHashMap<>();
        /** Taux moyen des credits immobiliers, partage par tous les foyers. */
        private final fr.patria.market.HousingRates rates;

        Sites(Config config, AccountRepository accounts) {
            this.config = config;
            this.accounts = accounts;
            this.rates = new fr.patria.market.HousingRates(config.clock());
        }

        HouseholdSite site(long householdId) {
            return open.computeIfAbsent(householdId, id -> {
                Household household = accounts.household(id).orElseThrow(() -> new NotFoundException("Foyer introuvable", "Ce foyer n'existe plus."));
                return HouseholdSite.open(file(household), config.clock(), rates);
            });
        }

        Path file(Household household) {
            Path primary = config.databasePath().toAbsolutePath();
            return household.isPrimary() ? primary : primary.resolveSibling(household.file());
        }

        @Override
        public List<Member> members(long householdId) {
            return site(householdId).household.people().stream().map(person -> new Member(person.id(), person.name(), person.isAccount())).toList();
        }

        @Override
        public void created(Household household) {
            site(household.id());
        }

        @Override
        public void deleted(Household household) {
            HouseholdSite site = open.remove(household.id());
            if (site != null) site.close();
            Path file = file(household);
            String suffix = ".supprime-" + LocalDateTime.now(config.clock()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            try {
                for (String extension : List.of("", "-wal", "-shm")) {
                    Path part = file.resolveSibling(file.getFileName() + extension);
                    if (Files.exists(part)) Files.move(part, file.resolveSibling(file.getFileName() + extension + suffix), StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException exception) {
                LOG.warning("Base du foyer supprimé non renommée : " + file + " (" + exception.getMessage() + ")");
            }
        }

        void closeAll() {
            open.values().forEach(HouseholdSite::close);
            open.clear();
        }
    }

    public static void main(String[] args) {
        App app = start(Config.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        LOG.info("Patria écoute sur le port " + app.port() + ".");
    }

    public static App start(Config config) {
        try {
            if (config.databasePath().toAbsolutePath().getParent() != null) Files.createDirectories(config.databasePath().toAbsolutePath().getParent());
            Database accountsDatabase = Database.open(config.databasePath().toAbsolutePath().resolveSibling(ACCOUNTS_FILE));
            AccountRepository accounts = new AccountRepository(accountsDatabase);
            accounts.ensurePrimary("Mon foyer", LocalDate.now(config.clock()));
            Sites sites = new Sites(config, accounts);
            sites.site(accounts.households().getFirst().id());

            Sessions sessions = new Sessions(config.clock());
            Router router = new Router(sessions, session -> viewer(session, accounts), household -> sites.site(household).router);
            router.publicGet("/sante", request -> new Response.Text(200, "ok"));
            new AuthController(sessions, config.adminLogin(), config.password(), new AuthController.Users() {
                @Override
                public Optional<Long> authenticate(String login, String password) {
                    return accounts.authenticate(login, password).map(User::id);
                }

                @Override
                public OptionalLong firstHousehold(Long userId) {
                    if (userId == null) return OptionalLong.of(accounts.households().getFirst().id());
                    return accounts.accesses(userId).stream().mapToLong(fr.patria.account.Access::householdId).findFirst();
                }
            }, config.clock()).register(router);
            new AccountController(accounts, sites, sessions, config.adminLogin(), config.clock()).register(router);
            router.post("/administration/bien/{id}/foyer", request -> {
                long target;
                try {
                    target = Long.parseLong(request.form().raw("foyer"));
                } catch (NumberFormatException exception) {
                    throw new fr.patria.common.ValidationException("Foyer invalide.");
                }
                long from = request.viewer().household().id();
                if (target == from || accounts.household(target).isEmpty()) throw new fr.patria.common.ValidationException("Choisissez un autre foyer.");
                HouseholdSite source = sites.site(from);
                if (source.properties.find(request.id()).isEmpty()) throw new NotFoundException("Bien introuvable", "Ce bien n'existe pas.");
                long moved = PropertyMove.move(source.database, sites.file(accounts.household(from).orElseThrow()), sites.site(target).database, request.id());
                sessions.select(request.session(), target);
                return Response.redirect("/administration/bien/" + moved + "?ok=deplace");
            });

            HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            server.createContext("/", router);
            server.setExecutor(executor);
            server.start();
            return new App(server, executor, accountsDatabase, sites);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Droits de la session : l'administrateur voit tout ; un utilisateur actif, les foyers et vues ouverts. Le foyer
     * de la session est garde s'il est encore accessible, sinon le premier.
     */
    static Optional<Viewer> viewer(Session session, AccountRepository accounts) {
        if (session.isAdmin()) {
            List<Viewer.Household> households = accounts.households().stream().map(household -> new Viewer.Household(household.id(), household.name())).toList();
            if (households.isEmpty()) return Optional.empty();
            Viewer.Household current = households.stream().filter(household -> household.id() == session.householdId()).findFirst().orElse(households.getFirst());
            return Optional.of(Viewer.admin("Admin", current, households));
        }
        Optional<User> user = accounts.user(session.userId()).filter(User::active);
        if (user.isEmpty()) return Optional.empty();
        List<fr.patria.account.Access> accesses = accounts.accesses(session.userId());
        if (accesses.isEmpty()) return Optional.empty();
        fr.patria.account.Access access = accesses.stream().filter(candidate -> candidate.householdId() == session.householdId()).findFirst().orElse(accesses.getFirst());
        List<Viewer.Household> households = accesses.stream().map(candidate -> new Viewer.Household(candidate.householdId(), candidate.householdName())).toList();
        return Optional.of(new Viewer(session.userId(), user.get().name(), new Viewer.Household(access.householdId(), access.householdName()), households,
                access.wholeHousehold(), access.members(), access.realEstate()));
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
        sites.closeAll();
        accountsDatabase.close();
    }
}
