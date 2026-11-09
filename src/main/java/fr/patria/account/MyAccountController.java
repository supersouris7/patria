package fr.patria.account;

import fr.patria.common.ForbiddenException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** « Mon compte » de chacun : mot de passe (provisoire a changer), double authentification, dernieres connexions, avis, choix du foyer. */
public final class MyAccountController extends AccountSupport {
    public MyAccountController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.userGet("/compte", request -> Response.page(accountPage(request, request.confirmation())));
        router.userPost("/compte/mot-de-passe", this::changePassword);
        router.userGet("/compte/nouveau-mot-de-passe", request -> Response.page(newPasswordPage(request, null)));
        router.userGet("/compte/double-authentification", request -> Response.page(twoFactorSetup(request, null)));
        router.userPost("/compte/double-authentification/activer", this::enableTwoFactor);
        router.userPost("/compte/double-authentification/desactiver", this::disableTwoFactor);
        router.userGet("/compte/email", request -> {
            if (request.viewer().isAdministrator()) return Response.redirect("/compte");
            AccountRepository.Email email = accounts.email(request.viewer().userId());
            return Response.page(request.viewer().emailToConfirm() ? AccountPages.emailRequired(email, request.confirmation(), request.csrf())
                    : accountPage(request, request.confirmation()));
        });
        router.userPost("/compte/email", this::changeEmail);
        router.publicGet("/compte/email/confirmer", request -> {
            java.util.Optional<AccountRepository.Token> token = accounts.consumeToken(request.query("jeton"), "verify", LocalDateTime.now(clock));
            token.ifPresent(found -> accounts.setEmail(found.userId(), found.email(), true));
            // Premier passage : la double authentification est proposee juste apres.
            if (token.isPresent() && accounts.offerTwoFactor(token.get().userId()) && !twoFactors.twoFactor(token.get().userId()).enabled()) {
                return Response.redirect("/compte/securiser");
            }
            return Response.page(fr.patria.ui.Layout.message(token.isPresent() ? "E-mail confirmé" : "Lien expiré",
                    token.isPresent() ? "Merci : votre e-mail est confirmé. Vous pouvez continuer dans Patria."
                            : "Ce lien est inconnu, déjà utilisé ou expiré. Demandez-en un nouveau depuis Mon compte.", request.csrf()));
        });
        router.userGet("/compte/securiser", request -> Response.page(AccountPages.offerTwoFactor(request.csrf())));
        router.userPost("/compte/securiser/plus-tard", request -> {
            if (!request.viewer().isAdministrator()) accounts.stopOfferingTwoFactor(request.viewer().userId());
            return Response.redirect(request.viewer().isAdministrator() || accounts.welcomed(request.viewer().userId()) ? "/" : "/bienvenue");
        });
        router.userPost("/compte/avis-lu", request -> {
            twoFactors.clearTwoFactorNotice(request.viewer().userId());
            return Response.redirect("/");
        });
        router.userPost("/acces/vu", request -> {
            if (!request.viewer().isAdministrator()) accounts.setAudienceSeen(request.viewer().userId(), LocalDateTime.now(clock));
            String next = fr.patria.web.LocalPath.safe(request.form().raw("suite"));
            return Response.redirect(next == null ? "/" : next);
        });
        router.userPost("/foyer/choisir", request -> {
            long household;
            try {
                household = Long.parseLong(request.form().raw("foyer"));
            } catch (NumberFormatException exception) {
                throw new ValidationException("Foyer invalide.");
            }
            if (request.viewer().isAdministrator() || request.viewer().households().stream().noneMatch(choice -> choice.id() == household)) {
                throw new ForbiddenException("Ce foyer n'est pas ouvert pour votre compte.");
            }
            sessions.select(request.session(), household);
            String next = fr.patria.web.LocalPath.safe(request.form().raw("suite"));
            return Response.redirect(next != null && next.startsWith("/administration") ? next : "/");
        });
    }

    private fr.patria.ui.Html accountPage(Request request, Notice notice) {
        Long id = request.viewer().userId();
        String login = id == null ? adminLogin : user(id).login();
        List<SecurityLog.LoginEvent> events = log.loginEventsOf(login, LocalDateTime.now(clock).plusMinutes(1), 10);
        Map<String, String> places = new java.util.HashMap<>();
        events.forEach(event -> places.computeIfAbsent(event.ip(), locator::describe));
        AccountPages.Security security = new AccountPages.Security(twoFactors.twoFactor(id), id == null && Exposure.exposed(request, log), events, places);
        if (id == null) return AccountPages.account(request.viewer().name(), adminLogin, true, security, notice, request.csrf());
        User user = user(id);
        return AccountPages.account(user.name(), user.login(), false, security, accounts.email(id), accounts.mail().active(), notice, request.csrf());
    }

    private fr.patria.ui.Html twoFactorSetup(Request request, Notice notice) {
        Long id = request.viewer().userId();
        TwoFactorStore.TwoFactor state = twoFactors.twoFactor(id);
        if (state.enabled()) throw new ValidationException("La double authentification est déjà activée.");
        String secret = state.pending();
        if (secret == null) {
            secret = fr.patria.common.Totp.newSecret();
            twoFactors.setTwoFactorPending(id, secret);
        }
        String login = id == null ? adminLogin : user(id).login();
        boolean welcome = "bienvenue".equals(request.query("suite")) || "bienvenue".equals(request.method().equals("POST") ? request.form().raw("suite") : "");
        return AccountPages.twoFactorSetup(fr.patria.common.Totp.uri("Patria", login, secret), secret, notice, welcome, request.csrf());
    }

    private Response enableTwoFactor(Request request) {
        Long id = request.viewer().userId();
        TwoFactorStore.TwoFactor state = twoFactors.twoFactor(id);
        if (state.pending() == null) return Response.redirect("/compte/double-authentification");
        long step = fr.patria.common.Totp.verify(state.pending(), request.form().raw("code"), clock.instant().getEpochSecond(), -1);
        if (step < 0) {
            return Response.invalid(twoFactorSetup(request, Notice.error("Code incorrect. Vérifiez l'heure du téléphone, puis tapez le code affiché maintenant.")));
        }
        twoFactors.enableTwoFactor(id, step, LocalDate.now(clock));
        if (id != null) accounts.stopOfferingTwoFactor(id);
        log.audit(LocalDateTime.now(clock), request.viewer().name(), "TWO_FACTOR", null, id, "Double authentification activée par « " + request.viewer().name() + " ».");
        // Activee depuis la proposition de la premiere connexion : on continue vers l'accueil.
        boolean welcome = "bienvenue".equals(request.form().raw("suite")) && id != null && !accounts.welcomed(id);
        return Response.redirect(welcome ? "/bienvenue" : "/compte?ok=double-authentification-active");
    }

    private Response disableTwoFactor(Request request) {
        Long id = request.viewer().userId();
        TwoFactorStore.TwoFactor state = twoFactors.twoFactor(id);
        if (!state.enabled()) return Response.redirect("/compte");
        long step = fr.patria.common.Totp.verify(state.secret(), request.form().raw("code"), clock.instant().getEpochSecond(), state.lastStep());
        if (step < 0) return Response.invalid(accountPage(request, Notice.error("Code incorrect : la double authentification reste activée.")));
        twoFactors.disableTwoFactor(id, null);
        log.audit(LocalDateTime.now(clock), request.viewer().name(), "TWO_FACTOR", null, id, "Double authentification désactivée par « " + request.viewer().name() + " ».");
        return Response.redirect("/compte?ok=double-authentification-inactive");
    }

    /**
     * Nouvel e-mail : avec l'envoi regle, un lien de confirmation part (valable 24 h, 3 envois par heure au plus) et
     * l'adresse reste « a confirmer » ; sans envoi, elle est simplement enregistree.
     */
    private Response changeEmail(Request request) {
        if (request.viewer().isAdministrator()) throw new ValidationException("L'administrateur n'a pas d'e-mail de compte.");
        long id = request.viewer().userId();
        AccountRepository.Mail mail = accounts.mail();
        try {
            String address = Mails.address(request.form().raw("email"));
            if (address.isEmpty()) throw new ValidationException("Indiquez un e-mail.");
            if (!mail.active()) {
                accounts.setEmail(id, address, false);
                return Response.redirect("/compte?ok=enregistre");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            java.util.List<LocalDateTime> sent = recentMails.compute(id, (key, times) -> {
                java.util.List<LocalDateTime> kept = new java.util.ArrayList<>(times == null ? java.util.List.of() : times);
                kept.removeIf(time -> time.isBefore(now.minusHours(1)));
                return kept;
            });
            if (sent.size() >= 3) throw new ValidationException("Trois liens envoyés dans l'heure : vérifiez vos courriers indésirables, puis réessayez plus tard.");
            sent.add(now);
            accounts.setEmail(id, address, false);
            String token = accounts.createToken(id, "verify", address, now.plusHours(24));
            Mails.send(mail, address, "Patria : confirmez votre e-mail", "Bonjour " + request.viewer().name() + ",\n\nPour confirmer votre e-mail, ouvrez ce lien "
                    + "(valable 24 heures) :\n" + Mails.link(mail, "/compte/email/confirmer?jeton=" + token));
            log.audit(now, request.viewer().name(), "USER", null, id, "E-mail du compte changé, lien de confirmation envoyé.");
            Notice notice = Notice.success("Lien envoyé à " + address + " : ouvrez-le pour confirmer.");
            return Response.page(request.viewer().emailToConfirm() ? AccountPages.emailRequired(accounts.email(id), notice, request.csrf()) : accountPage(request, notice));
        } catch (ValidationException exception) {
            Notice notice = Notice.error(exception.getMessage());
            return Response.invalid(request.viewer().emailToConfirm() ? AccountPages.emailRequired(accounts.email(id), notice, request.csrf()) : accountPage(request, notice));
        }
    }

    private final java.util.Map<Long, java.util.List<LocalDateTime>> recentMails = new java.util.concurrent.ConcurrentHashMap<>();

    /** Mot de passe choisi par l'administrateur (creation ou reinitialisation) : a changer avant toute autre page. */
    private fr.patria.ui.Html newPasswordPage(Request request, Notice notice) {
        if (request.viewer().isAdministrator()) throw new ForbiddenException("Le mot de passe de l'administrateur se change dans la configuration du serveur.");
        User user = user(request.viewer().userId());
        java.util.Optional<LocalDate> setOn = accounts.passwordToChange(user.id());
        return AccountPages.newPassword(user, setOn.filter(date -> !date.equals(LocalDate.MIN)).orElse(null), setOn.isPresent(), notice, request.csrf());
    }

    private Response changePassword(Request request) {
        if (request.viewer().isAdministrator()) throw new ValidationException("Le mot de passe de l'administrateur se change dans la configuration du serveur.");
        long id = request.viewer().userId();
        boolean forced = accounts.passwordToChange(id).isPresent();
        Map<String, String> form = request.form().values();
        try {
            if (!accounts.checkPassword(id, form.getOrDefault("current", ""))) throw new ValidationException("Mot de passe actuel incorrect.");
            if (!form.getOrDefault("password", "").equals(form.getOrDefault("confirm", ""))) {
                throw new ValidationException("La confirmation ne correspond pas au nouveau mot de passe.");
            }
            if (form.getOrDefault("password", "").equals(form.getOrDefault("current", ""))) {
                throw new ValidationException("Choisissez un mot de passe différent de l'actuel.");
            }
            accounts.setPassword(id, form.getOrDefault("password", ""));
            sessions.removeOtherSessions(request.session());
            if (forced && accounts.offerTwoFactor(id) && !twoFactors.twoFactor(id).enabled()) return Response.redirect("/compte/securiser");
            if (forced) return Response.redirect(accounts.welcomed(id) ? "/" : "/bienvenue");
            return Response.redirect("/compte?ok=mot-de-passe");
        } catch (ValidationException exception) {
            return Response.invalid(forced ? newPasswordPage(request, Notice.error(exception.getMessage())) : accountPage(request, Notice.error(exception.getMessage())));
        }
    }
}
