package fr.patria.account;

import fr.patria.common.ForbiddenException;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import fr.patria.web.Sessions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Foyers et utilisateurs (administrateur), « Mon compte » et choix du foyer affiche (tout le monde). */
public final class AccountController {
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{1,39}");
    private final AccountRepository accounts;
    private final HouseholdDirectory directory;
    private final Sessions sessions;
    private final String adminLogin;
    private final Clock clock;

    public AccountController(AccountRepository accounts, HouseholdDirectory directory, Sessions sessions, String adminLogin, Clock clock) {
        this.accounts = accounts;
        this.directory = directory;
        this.sessions = sessions;
        this.adminLogin = adminLogin;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/foyers", request -> Response.page(householdsPage(request, Map.of(), request.confirmation())));
        router.post("/administration/foyers/{id}/membres", request -> {
            Household household = household(request.id());
            try {
                String kind = request.form().raw("kind");
                if (!kind.equals("PERSON") && !kind.equals("ACCOUNT")) throw new ValidationException("Type de membre invalide.");
                directory.addMember(household.id(), request.form().requiredText("name", "Nom du membre", 60), kind.equals("ACCOUNT"));
                return Response.redirect("/administration/foyers?ok=enregistre#foyer-" + household.id());
            } catch (ValidationException exception) {
                return Response.invalid(householdsPage(request, Map.of(), Notice.error(exception.getMessage())));
            }
        });
        router.post("/administration/foyers", this::createHousehold);
        router.post("/administration/foyers/{id}/renommer", request -> {
            Household household = household(request.id());
            try {
                accounts.renameHousehold(household.id(), request.form().requiredText("name", "Nom du foyer", 60));
                return Response.redirect("/administration/foyers?ok=enregistre");
            } catch (ValidationException exception) {
                return Response.invalid(householdsPage(request, Map.of(), Notice.error(exception.getMessage())));
            }
        });
        router.post("/administration/foyers/{id}/supprimer", this::deleteHousehold);

        router.get("/administration/utilisateurs", request -> {
            String member = request.query("membre");
            return Response.page(usersPage(member == null ? Map.of() : Map.of("membre", member), request.confirmation(), request.csrf()));
        });
        router.post("/administration/utilisateurs", this::createUser);
        router.get("/administration/utilisateur/{id}", request -> Response.page(userPage(user(request.id()), request.confirmation(), request.csrf())));
        router.post("/administration/utilisateur/{id}", request -> {
            User user = user(request.id());
            try {
                boolean active = request.form().raw("active").equals("on");
                accounts.updateUser(user.id(), request.form().requiredText("name", "Nom affiché", 60), active);
                if (!active) sessions.removeUser(user.id());
                return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=enregistre");
            } catch (ValidationException exception) {
                return Response.invalid(userPage(user, Notice.error(exception.getMessage()), request.csrf()));
            }
        });
        router.post("/administration/utilisateur/{id}/mot-de-passe", request -> {
            User user = user(request.id());
            try {
                accounts.setPassword(user.id(), request.form().raw("password"));
                sessions.removeUser(user.id());
                return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=mot-de-passe");
            } catch (ValidationException exception) {
                return Response.invalid(userPage(user, Notice.error(exception.getMessage()), request.csrf()));
            }
        });
        router.post("/administration/utilisateur/{id}/foyer/{foyer}", this::saveAccess);
        router.post("/administration/utilisateur/{id}/supprimer", request -> {
            User user = user(request.id());
            accounts.deleteUser(user.id());
            directory.forgetUser(user.id());
            sessions.removeUser(user.id());
            return Response.redirect("/administration/utilisateurs?ok=supprime");
        });

        router.userGet("/compte", request -> Response.page(accountPage(request, request.confirmation())));
        router.userPost("/compte/mot-de-passe", this::changePassword);
        router.userPost("/foyer/choisir", request -> {
            long household;
            try {
                household = Long.parseLong(request.form().raw("foyer"));
            } catch (NumberFormatException exception) {
                throw new ValidationException("Foyer invalide.");
            }
            if (request.viewer().households().stream().noneMatch(choice -> choice.id() == household)) {
                throw new ForbiddenException("Ce foyer n'est pas ouvert pour votre compte.");
            }
            sessions.select(request.session(), household);
            String next = fr.patria.web.LocalPath.safe(request.form().raw("suite"));
            return Response.redirect(next != null && next.startsWith("/administration") ? next : "/");
        });
    }

    // ---- Foyers ----

    private fr.patria.ui.Html householdsPage(Request request, Map<String, String> values, Notice notice) {
        List<User> users = accounts.users();
        Map<Long, List<Access>> accesses = users.stream().collect(Collectors.toMap(User::id, user -> accounts.accesses(user.id())));
        List<AccountPages.HouseholdCard> cards = accounts.households().stream().map(household -> new AccountPages.HouseholdCard(household,
                directory.members(household.id()), users.stream().filter(user -> accesses.get(user.id()).stream().anyMatch(access -> access.householdId() == household.id()))
                .map(user -> user.name() + (user.active() ? "" : " (désactivé)")).toList())).toList();
        return AccountPages.households(cards, request.viewer().household().id(), values, notice, request.csrf());
    }

    private Response createHousehold(Request request) {
        try {
            Household household = accounts.createHousehold(request.form().requiredText("name", "Nom du foyer", 60), LocalDate.now(clock));
            directory.created(household);
            return Response.redirect("/administration/foyers?ok=foyer#foyer-" + household.id());
        } catch (ValidationException exception) {
            return Response.invalid(householdsPage(request, request.form().values(), Notice.error(exception.getMessage())));
        }
    }

    private Response deleteHousehold(Request request) {
        Household household = household(request.id());
        if (household.isPrimary()) throw new ValidationException("Le foyer principal ne peut pas être supprimé.");
        if (accounts.households().size() < 2) throw new ValidationException("Il faut garder au moins un foyer.");
        accounts.deleteHousehold(household.id());
        directory.deleted(household);
        return Response.redirect("/administration/foyers?ok=supprime");
    }

    private Household household(long id) {
        return accounts.household(id).orElseThrow(() -> new NotFoundException("Foyer introuvable", "Ce foyer n'existe pas."));
    }

    // ---- Utilisateurs ----

    private fr.patria.ui.Html usersPage(Map<String, String> values, Notice notice, String csrf) {
        List<User> users = accounts.users();
        Map<Long, List<Access>> accesses = users.stream().collect(Collectors.toMap(User::id, user -> accounts.accesses(user.id())));
        List<AccountPages.MemberChoice> choices = new java.util.ArrayList<>();
        for (Household household : accounts.households()) {
            for (HouseholdDirectory.Member member : directory.members(household.id())) {
                choices.add(new AccountPages.MemberChoice(household.id() + ":" + member.id(), member.name() + (member.account() ? " (compte joint)" : ""), household.name()));
            }
            choices.add(new AccountPages.MemberChoice(household.id() + ":nouveau", "Nouveau membre, au nom de l'utilisateur", household.name()));
        }
        return AccountPages.users(users, accesses, choices, values, notice, csrf);
    }

    private Response createUser(Request request) {
        try {
            String login = request.form().requiredText("login", "Identifiant", 40).toLowerCase(Locale.ROOT);
            if (!LOGIN.matcher(login).matches()) {
                throw new ValidationException("Identifiant : de 2 à 40 caractères, lettres minuscules, chiffres, point, tiret ou souligné.");
            }
            if (login.equalsIgnoreCase(adminLogin)) throw new ValidationException("Cet identifiant est celui de l'administrateur.");
            String name = request.form().requiredText("name", "Nom affiché", 60);
            Long household = null;
            String member = request.form().raw("membre").trim();
            if (!member.isEmpty()) {
                String[] parts = member.split(":", 2);
                try {
                    household = household(Long.parseLong(parts[0])).id();
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException exception) {
                    throw new ValidationException("Membre invalide.");
                }
                if (parts.length < 2 || (!parts[1].equals("nouveau") && directory.members(household).stream().noneMatch(candidate -> String.valueOf(candidate.id()).equals(parts[1])))) {
                    throw new ValidationException("Ce membre n'existe plus : choisissez-en un autre.");
                }
            }
            long id = accounts.createUser(login, name, request.form().raw("password"), LocalDate.now(clock));
            if (household != null) {
                String[] parts = member.split(":", 2);
                long person = parts[1].equals("nouveau") ? directory.addMember(household, name, false) : Long.parseLong(parts[1]);
                Household target = household(household);
                accounts.setAccess(id, household, new Access(id, household, target.name(), false, Set.of(person), false));
            }
            return Response.redirect("/administration/utilisateur/" + id + "?ok=utilisateur");
        } catch (ValidationException exception) {
            Map<String, String> values = new java.util.HashMap<>(request.form().values());
            values.remove("password");
            return Response.invalid(usersPage(values, Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private fr.patria.ui.Html userPage(User user, Notice notice, String csrf) {
        Map<Long, Access> accesses = accounts.accesses(user.id()).stream().collect(Collectors.toMap(Access::householdId, access -> access));
        List<AccountPages.HouseholdAccess> households = accounts.households().stream().map(household -> new AccountPages.HouseholdAccess(household,
                directory.members(household.id()), java.util.Optional.ofNullable(accesses.get(household.id())))).toList();
        return AccountPages.user(user, households, notice, csrf);
    }

    /** Droits sur un foyer : sans « Accès », l'acces est retire ; sinon les vues cochees (au moins une). */
    private Response saveAccess(Request request) {
        User user = user(request.id());
        Household household = household(request.id("foyer"));
        Map<String, String> form = request.form().values();
        if (!"on".equals(form.get("access"))) {
            accounts.setAccess(user.id(), household.id(), null);
            return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=acces");
        }
        Set<Long> members = new LinkedHashSet<>();
        for (HouseholdDirectory.Member member : directory.members(household.id())) {
            if ("on".equals(form.get("member_" + member.id()))) members.add(member.id());
        }
        boolean whole = "on".equals(form.get("whole"));
        if (!whole && members.isEmpty()) {
            return Response.invalid(userPage(user, Notice.error("Ouvrez au moins une vue dans « " + household.name() + " » : le foyer ou un membre."), request.csrf()));
        }
        accounts.setAccess(user.id(), household.id(), new Access(user.id(), household.id(), household.name(), whole, members, "on".equals(form.get("estate"))));
        return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=acces");
    }

    private User user(long id) {
        return accounts.user(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable", "Cet utilisateur n'existe pas."));
    }

    // ---- Mon compte ----

    private fr.patria.ui.Html accountPage(Request request, Notice notice) {
        if (request.viewer().isAdmin()) return AccountPages.account(request.viewer().name(), adminLogin, true, notice, request.csrf());
        User user = user(request.viewer().userId());
        return AccountPages.account(user.name(), user.login(), false, notice, request.csrf());
    }

    private Response changePassword(Request request) {
        if (request.viewer().isAdmin()) throw new ValidationException("Le mot de passe de l'administrateur se change dans la configuration du serveur.");
        long id = request.viewer().userId();
        Map<String, String> form = request.form().values();
        try {
            if (!accounts.checkPassword(id, form.getOrDefault("current", ""))) throw new ValidationException("Mot de passe actuel incorrect.");
            if (!form.getOrDefault("password", "").equals(form.getOrDefault("confirm", ""))) {
                throw new ValidationException("La confirmation ne correspond pas au nouveau mot de passe.");
            }
            accounts.setPassword(id, form.getOrDefault("password", ""));
            sessions.removeOtherSessions(request.session());
            return Response.redirect("/compte?ok=mot-de-passe");
        } catch (ValidationException exception) {
            return Response.invalid(accountPage(request, Notice.error(exception.getMessage())));
        }
    }
}
