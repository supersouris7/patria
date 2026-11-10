// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
        router.userGet("/bienvenue", request -> Response.page(WelcomePages.page(request.viewer().name(), request.viewer().householdManager(),
                request.viewer().isAdministrator(), encrypted, request.csrf())));
        router.userPost("/bienvenue/terminer", request -> {
            accounts.setWelcomed(request.viewer().userId(), LocalDate.now(clock));
            String next = LocalPath.safe(request.form().raw("suite"));
            if (next != null) return Response.redirect(next);
            // Visite de l'administrateur depuis Securite : Foyers peut renvoyer vers Installation au premier lancement.
            boolean tour = !request.form().raw("visite").isEmpty();
            if (request.viewer().isAdministrator()) return Response.redirect(tour ? "/administration/securite?visite=1" : "/administration/foyers");
            return Response.redirect(tour ? "/?visite=1" : "/");
        });
    }
}
