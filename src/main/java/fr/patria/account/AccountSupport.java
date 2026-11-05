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

    /** Comptes gestionnaires d'un foyer (un seul en principe). */
    protected List<User> managers(Household household) {
        return accounts.accessesTo(household.id()).entrySet().stream().filter(entry -> entry.getValue().manager()).map(Map.Entry::getKey).toList();
    }

    /** Ce compte n'est plus gestionnaire : il garde ses autres vues ; sans aucune, il n'a plus acces au foyer. */
    protected void demote(User user, Household household) {
        Access access = accounts.accesses(user.id()).stream().filter(candidate -> candidate.householdId() == household.id()).findFirst().orElse(null);
        if (access == null || !access.manager()) return;
        boolean keeps = access.wholeHousehold() || !access.members().isEmpty();
        Access after = keeps ? new Access(user.id(), household.id(), household.name(), access.wholeHousehold(), access.members(), access.realEstate(), false) : null;
        accounts.setAccess(user.id(), household.id(), after);
        audit("ACCESS", household.id(), user.id(), user.name() + (after == null ? " : n'est plus gestionnaire, plus d'accès à ce foyer."
                : " : n'est plus gestionnaire (" + AudienceText.summary(after, directory.members(household.id())) + ")."));
    }

    /**
     * Ce compte devient le gestionnaire du foyer, a la place du precedent (qui garde ses autres vues). Ses autres vues
     * sont gardees ; un nouveau venu est relie au membre qui porte son nom. S'il est le premier gestionnaire, il
     * reprend la progression du guide de l'administrateur.
     */
    protected void makeManager(User user, Household household) {
        Access before = accounts.accesses(user.id()).stream().filter(access -> access.householdId() == household.id()).findFirst().orElse(null);
        if (before != null && before.manager()) return;
        for (User previous : managers(household)) demote(previous, household);
        List<HouseholdDirectory.Member> members = directory.members(household.id());
        boolean first = accounts.activeManagers(household.id()) == 0;
        Set<Long> linked = before != null ? before.members() : members.stream().filter(member -> member.name().equalsIgnoreCase(user.name()))
                .map(HouseholdDirectory.Member::id).collect(Collectors.toSet());
        Access access = new Access(user.id(), household.id(), household.name(), before == null || before.wholeHousehold(), linked, true, true);
        accounts.setAccess(user.id(), household.id(), access);
        if (first) directory.transferFromAdministrator(household.id(), user.id());
        audit("ACCESS", household.id(), user.id(), user.name() + " : " + (before == null ? "accès ouvert" : "accès modifié") + " ("
                + AudienceText.summary(access, members) + ").");
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

    /** Droits lus dans le formulaire d'acces ; {@code null} si « Accès » est decoche. */
    protected Access access(User user, Household household, Map<String, String> form) {
        if (!"on".equals(form.get("access"))) return null;
        boolean manager = "on".equals(form.get("manager"));
        Set<Long> members = new LinkedHashSet<>();
        for (HouseholdDirectory.Member member : directory.members(household.id())) {
            if ("on".equals(form.get("member_" + member.id()))) members.add(member.id());
        }
        boolean whole = "on".equals(form.get("whole"));
        if (!manager && !whole && members.isEmpty()) {
            throw new ValidationException("Ouvrez au moins une vue dans « " + household.name() + " » : le foyer, un membre, ou la gestion complète.");
        }
        return new Access(user.id(), household.id(), household.name(), whole, members, "on".equals(form.get("estate")), manager);
    }

    protected User user(long id) {
        return accounts.user(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable", "Cet utilisateur n'existe pas."));
    }
}
