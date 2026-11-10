// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.ui.Components;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mot de passe oublie, seulement quand l'administrateur a regle l'envoi d'e-mails : un lien valable 30 minutes part
 * vers l'e-mail confirme du compte. La reponse est la meme qu'un compte existe ou non ; les envois sont limites par
 * adresse IP et par compte. Le lien ouvre un formulaire : il n'est consomme qu'a l'envoi du nouveau mot de passe (les
 * logiciels de messagerie qui ouvrent les liens a l'avance ne le grillent pas). La double authentification reste
 * demandee a la connexion suivante.
 */
public final class PasswordResetController extends AccountSupport {
    private static final int PER_ADDRESS = 5;
    private static final int PER_ACCOUNT = 3;
    private final Map<String, List<LocalDateTime>> recent = new ConcurrentHashMap<>();

    public PasswordResetController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.publicGet("/connexion/oubli", request -> Response.page(askPage(null)));
        router.publicPost("/connexion/oubli", this::send);
        router.publicGet("/connexion/reinitialiser", request -> Response.page(resetPage(request.query("jeton"), null)));
        router.publicPost("/connexion/reinitialiser", this::reset);
    }

    private Response send(Request request) {
        AccountRepository.Mail mail = accounts.mail();
        if (!mail.active()) return Response.redirect("/connexion");
        LocalDateTime now = LocalDateTime.now(clock);
        if (!allowed("ip:" + request.clientAddress(), PER_ADDRESS, now)) {
            return new Response.Page(429, askPage(Notice.error("Trop de demandes depuis cette adresse : réessayez dans une heure.")));
        }
        String wanted = request.form().raw("compte").strip();
        if (wanted.length() > 254) wanted = wanted.substring(0, 254);
        accounts.userForReset(wanted).ifPresent(user -> {
            if (!allowed("user:" + user.id(), PER_ACCOUNT, now)) return;
            String address = accounts.email(user.id()).address();
            String token = accounts.createToken(user.id(), "reset", address, now.plusMinutes(30));
            try {
                Mails.send(mail, address, "Patria : réinitialiser votre mot de passe", "Bonjour " + user.name() + ",\n\nPour choisir un nouveau mot de passe, "
                        + "ouvrez ce lien (valable 30 minutes) :\n" + Mails.link(mail, "/connexion/reinitialiser?jeton=" + token)
                        + "\n\nVous n'avez rien demandé ? Ignorez ce message : votre mot de passe ne change pas.");
                log.audit(now, user.name(), "USER", null, user.id(), "Lien de réinitialisation du mot de passe envoyé à « " + user.login() + " ».");
            } catch (ValidationException ignored) {
                // Meme reponse dans tous les cas ; l'echec est dans le journal du serveur.
            }
        });
        return Response.page(askPage(Notice.success("Si un compte correspond, avec un e-mail confirmé, un lien vient d'y être envoyé (valable 30 minutes). "
                + "Pensez aux courriers indésirables.")));
    }

    private Response reset(Request request) {
        String token = request.form().raw("jeton");
        String password = request.form().raw("password");
        try {
            if (!password.equals(request.form().raw("confirm"))) throw new ValidationException("La confirmation ne correspond pas au nouveau mot de passe.");
            if (password.length() < AccountRepository.MIN_PASSWORD) {
                throw new ValidationException("Au moins " + AccountRepository.MIN_PASSWORD + " caractères.");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            AccountRepository.Token found = accounts.consumeToken(token, "reset", now)
                    .orElseThrow(() -> new ValidationException("Lien expiré ou déjà utilisé : demandez-en un nouveau."));
            accounts.setPassword(found.userId(), password);
            sessions.removeUser(found.userId());
            log.audit(now, "Mot de passe oublié", "USER", null, found.userId(), "Mot de passe réinitialisé par le lien envoyé à l'e-mail du compte.");
            return Response.redirect("/connexion?reinitialise");
        } catch (ValidationException exception) {
            return Response.invalid(resetPage(token, Notice.error(exception.getMessage())));
        }
    }

    private boolean allowed(String key, int limit, LocalDateTime now) {
        List<LocalDateTime> sent = recent.compute(key, (ignored, times) -> {
            List<LocalDateTime> kept = new java.util.ArrayList<>(times == null ? List.of() : times);
            kept.removeIf(time -> time.isBefore(now.minusHours(1)));
            return kept;
        });
        if (sent.size() >= limit) return false;
        sent.add(now);
        if (recent.size() > 10_000) recent.clear();
        return true;
    }

    private static Html askPage(Notice notice) {
        return Layout.document("Mot de passe oublié · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1 class="login-title">Mot de passe oublié</h1>%s
                <p class="muted">Indiquez votre identifiant ou votre e-mail : si votre compte a un e-mail confirmé, vous recevrez un lien pour choisir un nouveau
                mot de passe. Sinon, demandez à l'administrateur.</p>
                <form method="post" action="/connexion/oubli" class="stack"><label>Identifiant ou e-mail
                <input name="compte" maxlength="254" required autocapitalize="none" spellcheck="false" autocomplete="username"></label>
                <button type="submit">Recevoir le lien</button></form>
                <p class="muted"><a href="/connexion">Retour à la connexion</a></p></main>""", Layout.brand(), Components.notice(notice)));
    }

    private static Html resetPage(String token, Notice notice) {
        return Layout.document("Nouveau mot de passe · Patria", "login-page", Html.of("""
                <main class="login-card">%s<h1 class="login-title">Nouveau mot de passe</h1>%s
                <form method="post" action="/connexion/reinitialiser" class="stack"><input type="hidden" name="jeton" value="%s">
                <label>Nouveau mot de passe<input name="password" type="password" minlength="%s" required autocomplete="new-password"></label>
                <label>Confirmation<input name="confirm" type="password" required autocomplete="new-password"></label>
                <button type="submit">Changer le mot de passe</button></form>
                <p class="muted"><a href="/connexion">Retour à la connexion</a></p></main>""", Layout.brand(), Components.notice(notice), token == null ? "" : token,
                AccountRepository.MIN_PASSWORD));
    }
}
