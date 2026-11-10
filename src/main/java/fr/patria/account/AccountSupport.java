// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.web.Sessions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Socle des pages de comptes ({@link HouseholdsController}, {@link UsersController}, {@link MyAccountController}) :
 * depots, journal des droits, et regles communes (un gestionnaire par foyer, droits lus dans un formulaire).
 */
public abstract class AccountSupport {
    /** Depots et services des pages de comptes, fournis par l'assemblage. */
    public record Stores(AccountRepository accounts, SecurityLog log, TwoFactorStore twoFactors, HouseholdDirectory directory, Sessions sessions,
                         String adminLogin, IpLocator locator, Clock clock) {
    }

    protected static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{1,39}");
    protected static final String ADMIN = "Administrateur";
    protected final AccountRepository accounts;
    protected final SecurityLog log;
    protected final TwoFactorStore twoFactors;
    protected final HouseholdDirectory directory;
    protected final Sessions sessions;
    protected final String adminLogin;
    protected final IpLocator locator;
    protected final Clock clock;

    AccountSupport(Stores stores) {
        this.accounts = stores.accounts();
        this.log = stores.log();
        this.twoFactors = stores.twoFactors();
        this.directory = stores.directory();
        this.sessions = stores.sessions();
        this.adminLogin = stores.adminLogin();
        this.locator = stores.locator();
        this.clock = stores.clock();
    }

    /** Ligne du journal des droits, signee par l'administrateur (seul a regler les droits). */
    protected void audit(String action, Long household, Long user, String detail) {
        log.audit(LocalDateTime.now(clock), ADMIN, action, household, user, detail);
    }

    /**
     * Lien absolu d'une invitation : l'adresse publique reglee par l'administrateur (Securite → E-mail), sinon celle
     * de la page affichee (lien a transmettre soi-meme).
     */
    protected String link(fr.patria.web.Request request, String path) {
        AccountRepository.Mail mail = accounts.mail();
        if (!mail.publicUrl().isBlank()) return Mails.link(mail, path);
        String host = request.header("Host");
        return (request.isHttps() ? "https://" : "http://") + (host == null ? "localhost" : host) + path;
    }

    /** Pour la recherche des listes : sans accents ni majuscules. */
    protected static String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Page demandee d'une liste (de 1 a la derniere), avec sa recherche, son filtre et son tri. */
    protected static AdminPages.Listing listing(fr.patria.web.Request request, String query, String filter, String sort, int total) {
        int pages = Math.max(1, (total + AdminPages.Listing.SIZE - 1) / AdminPages.Listing.SIZE);
        int page;
        try {
            page = Math.clamp(Integer.parseInt(java.util.Objects.requireNonNullElse(request.query("page"), "1")), 1, pages);
        } catch (NumberFormatException exception) {
            page = 1;
        }
        return new AdminPages.Listing(query, filter, sort, page, pages, total);
    }

    /** Action faite par quelqu'un d'autre que l'administrateur (gestionnaire, personne invitee). */
    protected void audit(String actor, String action, Long household, Long user, String detail) {
        log.audit(LocalDateTime.now(clock), actor, action, household, user, detail);
    }

    protected Household household(long id) {
        return accounts.household(id).orElseThrow(() -> new NotFoundException("Foyer introuvable", "Ce foyer n'existe pas."));
    }

    protected String login(String raw) {
        String login = raw.trim().toLowerCase(Locale.ROOT);
        if (!LOGIN.matcher(login).matches()) {
            throw new ValidationException("Identifiant : de 2 à 40 caractères, lettres minuscules, chiffres, point, tiret ou souligné.");
        }
        if (login.equalsIgnoreCase(adminLogin)) throw new ValidationException("Cet identifiant est celui de l'administrateur.");
        return login;
    }

    /**
     * Une ligne du tableau des droits : {@code <prefix>member_<id>}, {@code <prefix>whole} (totaux et reglages
     * communs), {@code <prefix>estate} (immobilier) ; {@code <prefix>all} coche tout.
     */
    protected Access access(User user, Household household, Map<String, String> form, String prefix) {
        boolean all = "on".equals(form.get(prefix + "all"));
        Set<Long> members = new LinkedHashSet<>();
        for (HouseholdDirectory.Member member : directory.members(household.id())) {
            if (all || "on".equals(form.get(prefix + "member_" + member.id()))) members.add(member.id());
        }
        return new Access(user.id(), household.id(), household.name(), all || "on".equals(form.get(prefix + "whole")), members,
                all || "on".equals(form.get(prefix + "estate")));
    }

    protected User user(long id) {
        return accounts.user(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable", "Cet utilisateur n'existe pas."));
    }
}
