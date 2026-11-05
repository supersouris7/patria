package fr.patria.account;

import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Sessions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * Page Securite de l'administrateur : connexions echouees et reussies (avec pays et ville de l'adresse IP), adresses
 * bloquees, dernieres connexions par compte, journal des droits et etat de l'installation. Aucune donnee de foyer.
 */
public final class SecurityController {
    /** Ce que la page dit de l'installation, fourni par l'assemblage. */
    public record Installation(boolean encrypted, boolean offline, Supplier<List<String>> blocked, Supplier<Integer> backups) {
    }

    private final AccountRepository accounts;
    private final SecurityLog log;
    private final TwoFactorStore twoFactors;
    private final Sessions sessions;
    private final IpLocator locator;
    private final Installation installation;
    private final Clock clock;

    public SecurityController(AccountRepository accounts, SecurityLog log, TwoFactorStore twoFactors, Sessions sessions, IpLocator locator, Installation installation,
                              Clock clock) {
        this.accounts = accounts;
        this.log = log;
        this.twoFactors = twoFactors;
        this.sessions = sessions;
        this.locator = locator;
        this.installation = installation;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/securite", this::page);
    }

    private Response page(Request request) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<SecurityLog.LoginEvent> failures = log.loginEvents(false, 50);
        List<SecurityLog.LoginEvent> successes = log.loginEvents(true, 30);
        java.util.Map<String, String> places = new java.util.HashMap<>();
        for (SecurityLog.LoginEvent event : failures) places.computeIfAbsent(event.ip(), locator::describe);
        for (SecurityLog.LoginEvent event : successes) places.computeIfAbsent(event.ip(), locator::describe);
        SecurityPages.State state = new SecurityPages.State(request.isHttps(), !request.isHttps() && !Router.insecure(request), installation.encrypted(), installation.offline(),
                installation.backups().get(), Exposure.exposed(request, log), twoFactors.twoFactor(null).enabled());
        return Response.page(SecurityPages.page(state, log.failuresSince(now.minusDays(1)), log.failuresSince(now.minusDays(7)), installation.blocked().get(),
                failures, successes, places, accounts.users(), log.lastLogins(), sessions.openByUser(),
                accounts.users().stream().filter(user -> accounts.passwordToChange(user.id()).isPresent()).map(User::id).collect(java.util.stream.Collectors.toSet()),
                twoFactors.twoFactorAccounts(), log.auditLog(60), request.csrf()));
    }
}
