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

    /** Compte actif choisi dans une liste ({@code compte} : son identifiant). */
    protected User chosenUser(String raw) {
        try {
            return accounts.user(Long.parseLong(raw.trim())).filter(User::active)
                    .orElseThrow(() -> new ValidationException("Choisissez un compte actif."));
        } catch (NumberFormatException exception) {
            throw new ValidationException("Choisissez un compte.");
        }
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

    /** Droits lus dans le formulaire d'acces d'un utilisateur ; {@code null} si « Accès » est decoche. */
    protected Access access(User user, Household household, Map<String, String> form) {
        if (!"on".equals(form.get("access"))) return null;
        Access access = access(user, household, form, "");
        if (!access.any()) throw new ValidationException("Cochez au moins un accès dans « " + household.name() + " » : un membre, les totaux ou l'immobilier.");
        return access;
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
