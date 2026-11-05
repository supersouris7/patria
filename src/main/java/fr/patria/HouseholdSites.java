package fr.patria;

import fr.patria.account.AccountRepository;
import fr.patria.account.Household;
import fr.patria.account.HouseholdDirectory;
import fr.patria.account.User;
import fr.patria.common.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bases des foyers ouvertes, par identifiant de foyer : le foyer principal est DATABASE_PATH, les autres
 * {@code foyer-N.sqlite3} a cote. Ouvertes a la premiere visite (toutes au demarrage avec DB_ENCRYPTION_KEY) ; un
 * foyer supprime est mis de cote, renomme.
 */
final class HouseholdSites implements HouseholdDirectory {
    private static final Logger LOG = Logger.getLogger(HouseholdSites.class.getName());

    private final Config config;
    private final AccountRepository accounts;
    private final Map<Long, HouseholdSite> open = new ConcurrentHashMap<>();
    /** Taux moyen des credits immobiliers, partage par tous les foyers. */
    private final fr.patria.market.HousingRates rates;

    HouseholdSites(Config config, AccountRepository accounts) {
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
        for (Household household : accounts.households()) {
            HouseholdSite site = site(household.id());
            site.wizard.forgetUser(userId);
            site.healthPreferences.forgetUser(userId);
        }
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

    /** Copies « avant-migration » presentes dans le dossier des bases (page Securite). */
    int backups() {
        try (java.util.stream.Stream<Path> files = Files.list(config.databasePath().toAbsolutePath().getParent())) {
            return (int) files.filter(file -> file.getFileName().toString().contains(".avant-migration-")).count();
        } catch (IOException exception) {
            return 0;
        }
    }
}
