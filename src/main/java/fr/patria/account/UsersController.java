package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Utilisateurs (administrateur, seul a creer les comptes et regler les droits) : comptes, droits, mots de passe provisoires, double authentification. */
public final class UsersController extends AccountSupport {
    public UsersController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.get("/administration/utilisateurs", request -> Response.page(usersPage(request.confirmation(), request.csrf())));
        router.post("/administration/inscriptions", request -> {
            String mode = request.form().raw("mode");
            accounts.setSignupMode(mode.equals("open") || mode.equals("closed") ? mode : "invite");
            audit("USER", null, null, "Inscriptions : " + (mode.equals("open") ? "ouvertes" : mode.equals("closed") ? "fermées" : "sur invitation") + ".");
            return Response.redirect("/administration/utilisateurs?ok=enregistre");
        });
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

    private fr.patria.ui.Html usersPage(Notice notice, String csrf) {
        List<User> users = accounts.users();
        Map<Long, List<Access>> accesses = users.stream().collect(Collectors.toMap(User::id, user -> accounts.accesses(user.id())));
        return AdminPages.users(users, accesses, accounts.signupMode(), notice, csrf);
    }

    private fr.patria.ui.Html userPage(User user, Notice notice, String csrf) {
        List<Access> accesses = accounts.accesses(user.id());
        Map<Long, List<HouseholdDirectory.Member>> members = accesses.stream().collect(Collectors.toMap(Access::householdId, access -> directory.members(access.householdId())));
        return AdminPages.user(user, accesses, members, accounts.passwordToChange(user.id()).isPresent(), twoFactors.twoFactor(user.id()).enabledOn(), notice, csrf);
    }
}
