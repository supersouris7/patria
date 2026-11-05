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

    private final fr.patria.account.IpLocator locator;

    private App(HttpServer server, ExecutorService executor, Database accountsDatabase, Sites sites, fr.patria.account.IpLocator locator) {
        this.server = server;
        this.executor = executor;
        this.accountsDatabase = accountsDatabase;
        this.sites = sites;
        this.locator = locator;
    }

    /** Empreinte d'un jeton d'appareil : la base ne garde jamais le jeton lui-meme. */
    static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Copies « avant-migration » presentes dans le dossier des bases. */
    private static int backups(Path directory) {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            return (int) files.filter(file -> file.getFileName().toString().contains(".avant-migration-")).count();
        } catch (IOException exception) {
            return 0;
        }
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
            this.rates = new fr.patria.market.HousingRates(config.clock(), config.offline());
        }

        HouseholdSite site(long householdId) {
            return open.computeIfAbsent(householdId, id -> {
                Household household = accounts.household(id).orElseThrow(() -> new NotFoundException("Foyer introuvable", "Ce foyer n'existe plus."));
                return HouseholdSite.open(file(household), config.encryptionKey(), config.clock(), config.offline(), rates, this::logins);
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
        public long addMember(long householdId, String name, boolean account) {
            return site(householdId).household.addMember(name, account ? fr.patria.household.Person.Kind.ACCOUNT : fr.patria.household.Person.Kind.PERSON);
        }

        /** Identifiants des utilisateurs lies a chaque membre d'un foyer (page Membres). */
        Map<Long, List<String>> logins(long householdId) {
            Map<Long, List<String>> result = new java.util.HashMap<>();
            for (User user : accounts.users()) {
                for (fr.patria.account.Access access : accounts.accesses(user.id())) {
                    if (access.householdId() != householdId) continue;
                    for (long person : access.members()) {
                        result.computeIfAbsent(person, key -> new java.util.ArrayList<>()).add(user.login() + (user.active() ? "" : " (désactivé)"));
                    }
                }
            }
            return result;
        }

        /** « foyer-2.sqlite3.supprime-20261104-101500 » : base d'un foyer supprime. */
        private static final java.util.regex.Pattern ARCHIVE = java.util.regex.Pattern.compile("foyer-\\d+\\.sqlite3\\.supprime-(\\d{8}-\\d{6})");

        @Override
        public List<Archive> archives() {
            Path directory = config.databasePath().toAbsolutePath().getParent();
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                return files.map(file -> file.getFileName().toString()).map(ARCHIVE::matcher).filter(java.util.regex.Matcher::matches).map(matcher -> {
                    Path file = directory.resolve(matcher.group());
                    long bytes;
                    try {
                        bytes = Files.size(file);
                    } catch (IOException exception) {
                        bytes = 0;
                    }
                    return new Archive(matcher.group(), LocalDateTime.parse(matcher.group(1), DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), bytes);
                }).sorted(java.util.Comparator.comparing(Archive::deletedOn).reversed()).toList();
            } catch (IOException exception) {
                return List.of();
            }
        }

        @Override
        public void purge(String name) {
            java.util.regex.Matcher matcher = ARCHIVE.matcher(name);
            if (!matcher.matches() || archives().stream().noneMatch(archive -> archive.name().equals(name))) {
                throw new fr.patria.common.ValidationException("Base introuvable.");
            }
            Path directory = config.databasePath().toAbsolutePath().getParent();
            String base = name.substring(0, name.indexOf(".supprime-"));
            String suffix = name.substring(name.indexOf(".supprime-"));
            try {
                for (String extension : List.of("", "-wal", "-shm")) Files.deleteIfExists(directory.resolve(base + extension + suffix));
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            LOG.info("Base d'un foyer supprimé effacée définitivement : " + name);
        }

        @Override
        public Progress progress(long householdId, long userId, boolean manager) {
            fr.patria.wizard.WizardRepository.Summary summary = site(householdId).wizard.summary(userId, manager);
            return new Progress(summary.done(), summary.skipped(), summary.total(), summary.next(), summary.reviewedOn());
        }

        @Override
        public void transferFromAdministrator(long householdId, long userId) {
            site(householdId).wizard.transferFromAdministrator(userId);
        }

        @Override
        public void forgetUser(long userId) {
            for (Household household : accounts.households()) site(household.id()).wizard.forgetUser(userId);
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
            if (config.encryptionKey() != null) {
                int encrypted = fr.patria.storage.Encryption.encryptLeftovers(config.databasePath().toAbsolutePath().getParent(), config.encryptionKey());
                if (encrypted > 0) LOG.info(encrypted + " sauvegarde(s) ou base(s) archivée(s) chiffrée(s).");
            }
            Database accountsDatabase = Database.open(config.databasePath().toAbsolutePath().resolveSibling(ACCOUNTS_FILE), config.encryptionKey());
            AccountRepository accounts = new AccountRepository(accountsDatabase);
            accounts.ensurePrimary("Mon foyer", LocalDate.now(config.clock()));
            if (config.resetAdminTwoFactor() && accounts.twoFactor(null).enabled()) {
                accounts.disableTwoFactor(null, null);
                accounts.audit(LocalDateTime.now(config.clock()), "Configuration", "TWO_FACTOR", null, null,
                        "Double authentification de l'administrateur retirée par ADMIN_2FA_RESET.");
                LOG.warning("Double authentification de l'administrateur retirée (ADMIN_2FA_RESET) : retirez cette variable de la configuration.");
            }
            Sites sites = new Sites(config, accounts);
            // Avec une cle, toutes les bases sont ouvertes (donc chiffrees) des le demarrage, pas a la premiere visite.
            if (config.encryptionKey() != null) accounts.households().forEach(household -> sites.site(household.id()));
            else sites.site(accounts.households().getFirst().id());

            fr.patria.ui.BuildInfo.useSupportUrl(config.support());
            Sessions sessions = new Sessions(config.clock());
            fr.patria.account.IpLocator locator = new fr.patria.account.IpLocator(accounts, config.offline(), config.clock());
            Router router = new Router(sessions, session -> viewer(session, accounts, sites, locator, config), household -> sites.site(household).router);
            router.publicGet("/sante", request -> new Response.Text(200, "ok"));
            router.publicGet("/aide/chiffrement", request -> Response.page(fr.patria.ui.SecurityPages.encryptionHelp(config.encryptionKey() != null, request.csrf())));
            router.publicGet("/aide/caviardage", request -> Response.page(fr.patria.ui.RedactionPage.render(request.csrf())));
            router.publicGet("/confidentialite", request -> Response.page(fr.patria.ui.PrivacyPage.render(config.offline(), config.encryptionKey() != null, request.csrf())));
            AuthController auth = new AuthController(sessions, config.adminLogin(), config.password(), new AuthController.Users() {
                @Override
                public Optional<Long> authenticate(String login, String password) {
                    return accounts.authenticate(login, password).map(User::id);
                }

                @Override
                public OptionalLong firstHousehold(Long userId) {
                    if (userId == null) return OptionalLong.of(accounts.households().getFirst().id());
                    return accounts.accesses(userId).stream().mapToLong(fr.patria.account.Access::householdId).findFirst();
                }

                @Override
                public boolean welcomed(Long userId) {
                    return accounts.welcomed(userId);
                }

                @Override
                public boolean mustChangePassword(Long userId) {
                    return userId != null && accounts.passwordToChange(userId).isPresent();
                }

                @Override
                public boolean twoFactor(Long userId) {
                    return accounts.twoFactor(userId).enabled();
                }

                @Override
                public boolean verifyCode(Long userId, String code) {
                    AccountRepository.TwoFactor state = accounts.twoFactor(userId);
                    if (!state.enabled()) return false;
                    long step = fr.patria.common.Totp.verify(state.secret(), code, config.clock().instant().getEpochSecond(), state.lastStep());
                    if (step < 0) return false;
                    accounts.useTwoFactorStep(userId, step);
                    return true;
                }

                @Override
                public boolean trusted(Long userId, String token) {
                    return token != null && !token.isBlank() && accounts.trustedDevice(userId, sha256(token), LocalDateTime.now(config.clock()));
                }

                @Override
                public String trustDevice(Long userId) {
                    byte[] bytes = new byte[32];
                    new java.security.SecureRandom().nextBytes(bytes);
                    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    LocalDateTime now = LocalDateTime.now(config.clock());
                    accounts.trustDevice(userId, sha256(token), now, now.plusDays(30));
                    return token;
                }

                @Override
                public void recorded(String login, Long userId, boolean success, String ip) {
                    accounts.recordLogin(LocalDateTime.now(config.clock()), login, userId, success, ip);
                    locator.lookUp(ip);
                }
            }, config.clock());
            auth.register(router);
            new AccountController(accounts, sites, sessions, config.adminLogin(), locator, config.clock()).register(router);
            Path dataDirectory = config.databasePath().toAbsolutePath().getParent();
            new fr.patria.account.SecurityController(accounts, sessions, locator, new fr.patria.account.SecurityController.Installation(
                    config.encryptionKey() != null, config.offline(), auth::blocked, () -> backups(dataDirectory)), config.clock()).register(router);
            new fr.patria.account.WelcomeController(accounts, config.encryptionKey() != null, config.clock()).register(router);
            // Deplacer un bien : un gestionnaire des deux foyers (l'administrateur ne voit pas les biens).
            router.userPost("/administration/bien/{id}/foyer", request -> {
                long target;
                try {
                    target = Long.parseLong(request.form().raw("foyer"));
                } catch (NumberFormatException exception) {
                    throw new fr.patria.common.ValidationException("Foyer invalide.");
                }
                long from = request.viewer().household().id();
                if (!request.viewer().manages() || request.viewer().isAdministrator()) {
                    throw new fr.patria.common.ForbiddenException("Seul un gestionnaire des deux foyers peut déplacer un bien.");
                }
                if (target == from || request.viewer().households().stream().noneMatch(household -> household.id() == target && household.managed())) {
                    throw new fr.patria.common.ValidationException("Choisissez un autre foyer dont vous êtes gestionnaire.");
                }
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
            return new App(server, executor, accountsDatabase, sites, locator);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Droits de la session : l'administrateur n'a aucune vue dans les foyers (le routeur ne lui en transmet aucune
     * page) ; un utilisateur actif, les foyers et vues ouverts (tout le foyer s'il en est gestionnaire), avec « qui
     * voit quoi » dans le foyer affiche. Le foyer de la session est garde s'il est encore accessible, sinon le premier.
     */
    static Optional<Viewer> viewer(Session session, AccountRepository accounts, HouseholdDirectory directory, fr.patria.account.IpLocator locator, Config config) {
        LocalDateTime started = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(session.expiresAt() - Sessions.LIFETIME.toMillis()), config.clock().getZone());
        if (session.isAdmin()) {
            List<Viewer.Household> households = accounts.households().stream().map(household -> new Viewer.Household(household.id(), household.name())).toList();
            if (households.isEmpty()) return Optional.empty();
            return Optional.of(Viewer.admin("Admin", households.getFirst(), households,
                    fr.patria.account.LoginHistory.recent(accounts, locator, config.adminLogin(), started)));
        }
        Optional<User> user = accounts.user(session.userId()).filter(User::active);
        if (user.isEmpty()) return Optional.empty();
        Viewer.Recent recent = fr.patria.account.LoginHistory.recent(accounts, locator, user.get().login(), started);
        LocalDate removed = accounts.twoFactor(session.userId()).removedByAdminOn();
        String notice = removed == null ? null : "L'administrateur a retiré votre double authentification le "
                + removed.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ". Si ce n'est pas vous qui l'avez demandé, changez votre mot de passe "
                + "et réactivez-la dans Mon compte.";
        List<fr.patria.account.Access> accesses = accounts.accesses(session.userId());
        if (accesses.isEmpty()) return Optional.empty();
        fr.patria.account.Access access = accesses.stream().filter(candidate -> candidate.householdId() == session.householdId()).findFirst().orElse(accesses.getFirst());
        List<Viewer.Household> households = accesses.stream()
                .map(candidate -> new Viewer.Household(candidate.householdId(), candidate.householdName(), candidate.manager())).toList();
        Viewer.Household current = new Viewer.Household(access.householdId(), access.householdName(), access.manager());
        boolean mustChange = accounts.passwordToChange(session.userId()).isPresent();
        Map<User, fr.patria.account.Access> shared = new java.util.LinkedHashMap<>();
        accounts.accessesTo(access.householdId()).forEach((other, rights) -> {
            if (other.active()) shared.put(other, rights);
        });
        Viewer.Audience audience = fr.patria.account.AudienceText.of(shared, directory.members(access.householdId()), session.userId(),
                accounts.accessChanges(access.householdId(), accounts.audienceSeen(session.userId())));
        if (access.manager()) return Optional.of(Viewer.manager(session.userId(), user.get().name(), current, households, mustChange, audience, recent, notice));
        return Optional.of(new Viewer(session.userId(), user.get().name(), current, households,
                access.wholeHousehold(), access.members(), access.realEstate(), false, mustChange, audience, recent, notice));
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
