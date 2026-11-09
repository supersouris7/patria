package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Utilisateurs (administrateur, seul a creer les comptes et regler les droits) : comptes, droits, mots de passe provisoires, double authentification. */
public final class UsersController extends AccountSupport {
    public UsersController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
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
                if (active != user.active()) audit("USER", null, user.id(), "Compte « " + user.login() + " » " + (active ? "réactivé." : "désactivé."));
                return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=enregistre");
            } catch (ValidationException exception) {
                return Response.invalid(userPage(user, Notice.error(exception.getMessage()), request.csrf()));
            }
        });
        router.post("/administration/utilisateur/{id}/mot-de-passe", request -> {
            User user = user(request.id());
            try {
                accounts.resetPassword(user.id(), request.form().raw("password"), LocalDate.now(clock));
                sessions.removeUser(user.id());
                audit("PASSWORD", null, user.id(), "Mot de passe de « " + user.login() + " » réinitialisé (provisoire, à changer à la connexion).");
                return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=mot-de-passe-provisoire");
            } catch (ValidationException exception) {
                return Response.invalid(userPage(user, Notice.error(exception.getMessage()), request.csrf()));
            }
        });
        router.post("/administration/utilisateur/{id}/foyer/{foyer}", this::saveAccess);
        router.post("/administration/utilisateur/{id}/supprimer", request -> {
            User user = user(request.id());
            List<Access> before = accounts.accesses(user.id());
            accounts.deleteUser(user.id());
            twoFactors.forget(user.id());
            directory.forgetUser(user.id());
            sessions.removeUser(user.id());
            for (Access access : before) audit("ACCESS", access.householdId(), user.id(), user.name() + " : compte supprimé, plus d'accès à ce foyer.");
            audit("USER", null, user.id(), "Compte « " + user.login() + " » supprimé.");
            return Response.redirect("/administration/utilisateurs?ok=supprime");
        });

        router.post("/administration/utilisateur/{id}/double-authentification/retirer", request -> {
            User user = user(request.id());
            twoFactors.disableTwoFactor(user.id(), LocalDate.now(clock));
            audit("TWO_FACTOR", null, user.id(), "Double authentification de « " + user.login() + " » retirée (l'utilisateur en est averti).");
            return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=double-authentification");
        });
    }

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
            String login = login(request.form().requiredText("login", "Identifiant", 40));
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
            String email = Mails.address(request.form().raw("email"));
            AccountRepository.Mail mail = accounts.mail();
            long id = accounts.createUser(login, name, request.form().raw("password"), LocalDate.now(clock), email.isEmpty() ? null : email, mail.required() && mail.active());
            audit("USER", null, id, "Compte « " + login + " » créé (mot de passe provisoire).");
            if (household != null) {
                String[] parts = member.split(":", 2);
                long person = parts[1].equals("nouveau") ? directory.addMember(household, name, false) : Long.parseLong(parts[1]);
                Household target = household(household);
                Access access = new Access(id, household, target.name(), false, Set.of(person), false);
                accounts.setAccess(id, household, access);
                audit("ACCESS", household, id, name + " : accès ouvert (" + AudienceText.summary(access, directory.members(household)) + ").");
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
        return AccountPages.user(user, households, accounts.passwordToChange(user.id()).isPresent(), twoFactors.twoFactor(user.id()).enabledOn(), notice, csrf);
    }

    /** Droits sur un foyer : sans « Accès », l'acces est retire ; sinon les vues cochees (au moins une). */
    private Response saveAccess(Request request) {
        User user = user(request.id());
        Household household = household(request.id("foyer"));
        Access before = accounts.accesses(user.id()).stream().filter(access -> access.householdId() == household.id()).findFirst().orElse(null);
        try {
            Access access = access(user, household, request.form().values());
            accounts.setAccess(user.id(), household.id(), access);
            List<HouseholdDirectory.Member> members = directory.members(household.id());
            String now = AudienceText.summary(access, members);
            if (before == null && access != null) audit("ACCESS", household.id(), user.id(), user.name() + " : accès ouvert (" + now + ").");
            else if (before != null && access == null) audit("ACCESS", household.id(), user.id(), user.name() + " : plus d'accès à ce foyer.");
            else if (before != null && !AudienceText.summary(before, members).equals(now)) {
                audit("ACCESS", household.id(), user.id(), user.name() + " : accès modifié (" + now + ").");
            }
        } catch (ValidationException exception) {
            return Response.invalid(userPage(user, Notice.error(exception.getMessage()), request.csrf()));
        }
        return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=acces");
    }
}
