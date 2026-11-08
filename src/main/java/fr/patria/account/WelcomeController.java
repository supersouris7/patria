package fr.patria.account;

import fr.patria.web.LocalPath;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Accueil de la premiere connexion ({@code /bienvenue}), montre une fois par compte puis rejouable depuis « Mon
 * compte ». La connexion y mene tant qu'il n'a pas ete vu.
 */
public final class WelcomeController {
    private final AccountRepository accounts;
    private final boolean encrypted;
    private final Clock clock;

    public WelcomeController(AccountRepository accounts, boolean encrypted, Clock clock) {
        this.accounts = accounts;
        this.encrypted = encrypted;
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/bienvenue", request -> {
            int step;
            try {
                step = Math.clamp(Integer.parseInt(request.query("etape") == null ? "1" : request.query("etape")), 1, WelcomePages.STEPS);
            } catch (NumberFormatException exception) {
                step = 1;
            }
            return Response.page(WelcomePages.page(step, request.viewer().name(), !request.viewer().isAdministrator(), request.viewer().isAdministrator(),
                    encrypted, request.csrf()));
        });
        router.userPost("/bienvenue/terminer", request -> {
            accounts.setWelcomed(request.viewer().userId(), LocalDate.now(clock));
            String next = LocalPath.safe(request.form().raw("suite"));
            if (next != null) return Response.redirect(next);
            boolean skipped = !request.form().raw("passer").isEmpty();
            if (request.viewer().isAdministrator()) return Response.redirect("/administration/foyers");
            return Response.redirect(!skipped ? "/assistant" : "/");
        });
    }
}
