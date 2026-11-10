// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
    /** Resultat du dernier reglage ou test d'envoi, affiche une fois sur la page Securite. */
    private volatile String mailStatus;

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
        router.post("/administration/securite/email", request -> {
            fr.patria.web.FormData form = request.form();
            AccountRepository.Mail current = accounts.mail();
            try {
                String host = form.raw("host").strip();
                int port = form.raw("port").isBlank() ? 587 : Integer.parseInt(form.raw("port").strip());
                if (port < 1 || port > 65535) throw new NumberFormatException();
                String from = Mails.address(form.raw("expediteur"));
                String url = form.raw("adresse").strip();
                if (!url.isEmpty() && !url.matches("https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/[A-Za-z0-9._~/-]*)?")) {
                    throw new fr.patria.common.ValidationException("Adresse publique invalide : par exemple https://patria.example.fr");
                }
                if (host.length() > 200 || !host.matches("[A-Za-z0-9.-]*")) throw new fr.patria.common.ValidationException("Serveur SMTP invalide.");
                String password = form.raw("mot_de_passe").isEmpty() ? current.smtp().password() : form.raw("mot_de_passe");
                fr.patria.common.Smtp.Settings smtp = new fr.patria.common.Smtp.Settings(host, port, fr.patria.common.Smtp.security(form.raw("securite")),
                        form.raw("utilisateur").strip(), password, from);
                AccountRepository.Mail mail = new AccountRepository.Mail(smtp, url, "on".equals(form.raw("exiger")));
                if (mail.required() && !mail.active()) {
                    throw new fr.patria.common.ValidationException("Pour exiger un e-mail confirmé, réglez d'abord le serveur, l'expéditeur et l'adresse publique.");
                }
                accounts.setMail(mail);
                mailStatus = mail.active() ? "Réglage enregistré. Envoyez un e-mail de test pour le vérifier." : "Réglage enregistré : envoi d'e-mails inactif.";
            } catch (NumberFormatException exception) {
                mailStatus = "Port invalide.";
            } catch (fr.patria.common.ValidationException exception) {
                mailStatus = exception.getMessage();
            }
            return Response.redirect("/administration/securite#email");
        });
        router.post("/administration/securite/email/test", request -> {
            try {
                String to = Mails.address(request.form().raw("a"));
                if (to.isEmpty()) throw new fr.patria.common.ValidationException("Indiquez une adresse.");
                Mails.send(accounts.mail(), to, "Patria : e-mail de test", "L'envoi d'e-mails de Patria fonctionne.");
                mailStatus = "E-mail de test envoyé à " + to + ".";
            } catch (fr.patria.common.ValidationException exception) {
                mailStatus = exception.getMessage();
            }
            return Response.redirect("/administration/securite#email");
        });
        router.post("/administration/securite/ia", request -> {
            try {
                fr.patria.ai.LocalAi.Settings settings = new fr.patria.ai.LocalAi.Settings("on".equals(request.form().raw("ia")), request.form().raw("url"),
                        request.form().raw("modele"));
                accounts.setLocalAi(settings);
                fr.patria.ai.LocalAi.configure(settings);
                fr.patria.ui.Components.useLocalAi(settings.enabled());
                fr.patria.ai.LocalAi.remember(settings.enabled() ? fr.patria.ai.LocalAi.check(settings) : null);
                return Response.redirect("/administration/securite?ok=enregistre#ia-locale");
            } catch (fr.patria.common.ValidationException exception) {
                fr.patria.ai.LocalAi.remember(exception.getMessage());
                return Response.redirect("/administration/securite#ia-locale");
            }
        });
    }

    private String takeMailStatus() {
        String status = mailStatus;
        mailStatus = null;
        return status;
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
                twoFactors.twoFactorAccounts(), log.auditLog(60), accounts.mail(), takeMailStatus(), request.csrf()));
    }
}
