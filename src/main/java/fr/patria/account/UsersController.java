// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
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
        router.get("/administration/utilisateurs", request -> InstallationController.blocked(accounts, twoFactors) ? Response.redirect("/compte/double-authentification")
                : Response.page(usersPage(request, request.confirmation())));
        router.post("/administration/utilisateurs/groupe", this::bulk);
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
            delete(user(request.id()));
            return Response.redirect("/administration/utilisateurs?ok=supprime");
        });

        router.post("/administration/utilisateur/{id}/double-authentification/retirer", request -> {
            User user = user(request.id());
            twoFactors.disableTwoFactor(user.id(), LocalDate.now(clock));
            audit("TWO_FACTOR", null, user.id(), "Double authentification de « " + user.login() + " » retirée (l'utilisateur en est averti).");
            return Response.redirect("/administration/utilisateur/" + user.id() + "?ok=double-authentification");
        });
    }

    /** Suppression d'un compte : droits, double authentification, progression et sessions. */
    private void delete(User user) {
        List<Access> before = accounts.accesses(user.id());
        accounts.deleteUser(user.id());
        twoFactors.forget(user.id());
        directory.forgetUser(user.id());
        sessions.removeUser(user.id());
        for (Access access : before) audit("ACCESS", access.householdId(), user.id(), user.name() + " : compte supprimé, plus d'accès à ce foyer.");
        audit("USER", null, user.id(), "Compte « " + user.login() + " » supprimé.");
    }

    /** Action sur les comptes coches : desactiver, reactiver ou supprimer. */
    private Response bulk(Request request) {
        String action = request.form().raw("action");
        List<User> chosen = accounts.users().stream().filter(user -> "on".equals(request.form().raw("u_" + user.id()))).toList();
        if (chosen.isEmpty()) return Response.invalid(usersPage(request, Notice.error("Cochez au moins un compte.")));
        for (User user : chosen) {
            switch (action) {
                case "supprimer" -> delete(user);
                case "desactiver", "reactiver" -> {
                    boolean active = action.equals("reactiver");
                    if (active == user.active()) continue;
                    accounts.updateUser(user.id(), user.name(), active);
                    if (!active) sessions.removeUser(user.id());
                    audit("USER", null, user.id(), "Compte « " + user.login() + " » " + (active ? "réactivé." : "désactivé."));
                }
                default -> throw new ValidationException("Action inconnue.");
            }
        }
        return Response.redirect("/administration/utilisateurs?ok=enregistre");
    }

    /** Liste des comptes : recherche (nom, identifiant, e-mail), filtre, tri, pages de 50, tuiles. */
    private fr.patria.ui.Html usersPage(Request request, Notice notice) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
        Map<Long, List<Access>> accesses = accounts.allAccesses();
        Map<Long, java.time.LocalDateTime> logins = log.lastLogins();
        Map<String, LocalDate> twoFactor = twoFactors.twoFactorAccounts();
        List<AdminPages.UserLine> all = accounts.userRows().stream().map(row -> new AdminPages.UserLine(row, accesses.getOrDefault(row.user().id(), List.of()),
                logins.get(row.user().id()), twoFactor.containsKey(TwoFactorStore.account(row.user().id())))).toList();
        AdminPages.UserStats stats = new AdminPages.UserStats(all.size(),
                (int) all.stream().filter(line -> line.lastLogin() != null && line.lastLogin().isAfter(now.minusDays(30))).count(),
                (int) all.stream().filter(line -> line.row().createdOn() != null && java.time.YearMonth.from(line.row().createdOn()).equals(java.time.YearMonth.from(now))).count(),
                (int) all.stream().filter(line -> line.row().user().active() && !line.twoFactor()).count(),
                (int) all.stream().filter(line -> !line.row().user().active()).count());
        String query = normalize(request.query("q"));
        String filter = java.util.Objects.requireNonNullElse(request.query("filtre"), "");
        String sort = java.util.Objects.requireNonNullElse(request.query("tri"), "");
        java.util.stream.Stream<AdminPages.UserLine> stream = all.stream().filter(line -> query.isEmpty() || normalize(line.row().user().name() + " " + line.row().user().login()
                + " " + java.util.Objects.requireNonNullElse(line.row().email(), "")).contains(query)).filter(line -> switch (filter) {
                    case "actifs" -> line.row().user().active();
                    case "desactives" -> !line.row().user().active();
                    case "sans2fa" -> line.row().user().active() && !line.twoFactor();
                    case "inactifs" -> line.row().user().active() && (line.lastLogin() == null || line.lastLogin().isBefore(now.minusDays(90)));
                    case "sansfoyer" -> line.accesses().isEmpty();
                    default -> true;
                });
        if (sort.equals("recents")) {
            stream = stream.sorted(java.util.Comparator.comparing((AdminPages.UserLine line) -> line.row().createdOn(), java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed());
        } else if (sort.equals("connexion")) {
            stream = stream.sorted(java.util.Comparator.comparing(AdminPages.UserLine::lastLogin, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed());
        }
        List<AdminPages.UserLine> found = stream.toList();
        AdminPages.Listing listing = listing(request, query.isEmpty() ? "" : request.query("q").strip(), filter, sort, found.size());
        List<AdminPages.UserLine> page = found.stream().skip((long) (listing.page() - 1) * AdminPages.Listing.SIZE).limit(AdminPages.Listing.SIZE).toList();
        return AdminPages.users(stats, page, listing, accounts.signupMode(), notice, request.csrf());
    }

    private fr.patria.ui.Html userPage(User user, Notice notice, String csrf) {
        List<Access> accesses = accounts.accesses(user.id());
        Map<Long, List<HouseholdDirectory.Member>> members = accesses.stream().collect(Collectors.toMap(Access::householdId, access -> directory.members(access.householdId())));
        return AdminPages.user(user, accesses, members, accounts.passwordToChange(user.id()).isPresent(), twoFactors.twoFactor(user.id()).enabledOn(), notice, csrf);
    }
}
