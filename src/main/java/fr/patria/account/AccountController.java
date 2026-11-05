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
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Foyers, utilisateurs et droits (administrateur, seul a creer les comptes et a regler les droits ; il ne voit
 * aucune donnee des foyers), « Mon compte », mot de passe provisoire a changer et choix du foyer affiche (tout le
 * monde). Chaque changement de droits est inscrit au journal (page Securite, et « qui voit quoi » des utilisateurs).
 */
public final class AccountController {
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{1,39}");
    private static final String ADMIN = "Administrateur";
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
                String name = request.form().requiredText("name", "Nom du membre", 60);
                directory.addMember(household.id(), name, kind.equals("ACCOUNT"));
                audit("MEMBER", household.id(), null, "Membre « " + name + " » ajouté au foyer « " + household.name() + " ».");
                return Response.redirect("/administration/foyers?ok=enregistre#foyer-" + household.id());
            } catch (ValidationException exception) {
                return Response.invalid(householdsPage(request, Map.of(), Notice.error(exception.getMessage())));
            }
        });
        router.post("/administration/foyers", this::createHousehold);
        router.post("/administration/foyers/reprise", this::takeOver);
        // Un seul gestionnaire par foyer : le choisir le remplace ; « Aucun » le retire.
        router.post("/administration/foyers/{id}/gestionnaire", request -> {
            Household household = household(request.id());
            try {
                String chosen = request.form().raw("compte").trim();
                if (chosen.isEmpty()) {
                    for (User manager : managers(household)) demote(manager, household);
                } else {
                    makeManager(chosenUser(chosen), household);
                }
                return Response.redirect("/administration/foyers?ok=" + (chosen.isEmpty() ? "acces" : "gestionnaire") + "#foyer-" + household.id());
            } catch (ValidationException exception) {
                return Response.invalid(householdsPage(request, Map.of(), Notice.error(exception.getMessage())));
            }
        });
        router.post("/administration/foyers/{id}/renommer", request -> {
            Household household = household(request.id());
            try {
                String name = request.form().requiredText("name", "Nom du foyer", 60);
                accounts.renameHousehold(household.id(), name);
                audit("HOUSEHOLD", household.id(), null, "Foyer « " + household.name() + " » renommé en « " + name + " ».");
                return Response.redirect("/administration/foyers?ok=enregistre");
            } catch (ValidationException exception) {
                return Response.invalid(householdsPage(request, Map.of(), Notice.error(exception.getMessage())));
            }
        });
        router.post("/administration/foyers/{id}/supprimer", this::deleteHousehold);
        router.post("/administration/foyers/archives/effacer", request -> {
            directory.purge(request.form().raw("archive"));
            audit("HOUSEHOLD", null, null, "Base d'un foyer supprimé effacée définitivement.");
            return Response.redirect("/administration/foyers?ok=supprime");
        });

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
            directory.forgetUser(user.id());
            sessions.removeUser(user.id());
            for (Access access : before) audit("ACCESS", access.householdId(), user.id(), user.name() + " : compte supprimé, plus d'accès à ce foyer.");
            audit("USER", null, user.id(), "Compte « " + user.login() + " » supprimé.");
            return Response.redirect("/administration/utilisateurs?ok=supprime");
        });

        router.userGet("/compte", request -> Response.page(accountPage(request, request.confirmation())));
        router.userPost("/compte/mot-de-passe", this::changePassword);
        router.userGet("/compte/nouveau-mot-de-passe", request -> Response.page(newPasswordPage(request, null)));
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

    /** Ligne du journal des droits, signee par l'administrateur (seul a regler les droits). */
    private void audit(String action, Long household, Long user, String detail) {
        accounts.audit(LocalDateTime.now(clock), ADMIN, action, household, user, detail);
    }

    // ---- Foyers ----

    private fr.patria.ui.Html householdsPage(Request request, Map<String, String> values, Notice notice) {
        List<User> users = accounts.users();
        List<AccountPages.HouseholdCard> cards = accounts.households().stream().map(household -> {
            Map<User, Access> shared = accounts.accessesTo(household.id());
            List<AccountPages.AccountProgress> progress = shared.entrySet().stream()
                    .filter(entry -> entry.getValue().manager() || !entry.getValue().members().isEmpty())
                    .map(entry -> new AccountPages.AccountProgress(entry.getKey(), entry.getValue().manager(),
                            directory.progress(household.id(), entry.getKey().id(), entry.getValue().manager()))).toList();
            List<User> managers = shared.entrySet().stream().filter(entry -> entry.getKey().active() && entry.getValue().manager()).map(Map.Entry::getKey).toList();
            return new AccountPages.HouseholdCard(household, directory.members(household.id()), managers.size(),
                    shared.keySet().stream().map(user -> user.name() + (user.active() ? "" : " (désactivé)")).toList(), progress, managers);
        }).toList();
        List<Household> orphans = cards.stream().filter(card -> card.managers() == 0).map(AccountPages.HouseholdCard::household).toList();
        return AccountPages.households(cards, orphans, users.stream().filter(User::active).toList(), directory.archives(), values, notice, request.csrf());
    }

    private Response createHousehold(Request request) {
        try {
            Household household = accounts.createHousehold(request.form().requiredText("name", "Nom du foyer", 60), LocalDate.now(clock));
            directory.created(household);
            audit("HOUSEHOLD", household.id(), null, "Foyer « " + household.name() + " » créé.");
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
        audit("HOUSEHOLD", household.id(), null, "Foyer « " + household.name() + " » supprimé (base mise de côté).");
        return Response.redirect("/administration/foyers?ok=supprime");
    }

    /**
     * Reprise des foyers sans gestionnaire : un nouveau compte devient gestionnaire des foyers coches, avec la
     * progression du guide de l'administrateur et le membre qui porte son nom. Sert au passage a la version ou
     * l'administrateur ne voit plus les finances.
     */
    private Response takeOver(Request request) {
        Map<String, String> form = request.form().values();
        try {
            List<Household> chosen = accounts.households().stream().filter(household -> "on".equals(form.get("foyer_" + household.id()))).toList();
            if (chosen.isEmpty()) throw new ValidationException("Cochez au moins un foyer à confier à ce compte.");
            if (!form.getOrDefault("compte", "").isBlank()) {
                // Compte existant : il devient gestionnaire des foyers coches.
                User user = chosenUser(form.get("compte"));
                for (Household household : chosen) makeManager(user, household);
                return Response.redirect("/administration/foyers?ok=gestionnaire");
            }
            String login = login(form.getOrDefault("login", ""));
            String name = request.form().requiredText("name", "Nom affiché", 60);
            long id = accounts.createUser(login, name, form.getOrDefault("password", ""), LocalDate.now(clock));
            audit("USER", null, id, "Compte « " + login + " » créé pour reprendre des foyers.");
            for (Household household : chosen) {
                Set<Long> members = directory.members(household.id()).stream().filter(member -> member.name().equalsIgnoreCase(name))
                        .map(HouseholdDirectory.Member::id).collect(Collectors.toSet());
                Access access = new Access(id, household.id(), household.name(), true, members, true, true);
                accounts.setAccess(id, household.id(), access);
                directory.transferFromAdministrator(household.id(), id);
                audit("ACCESS", household.id(), id, name + " : accès ouvert (" + AudienceText.summary(access, directory.members(household.id())) + ").");
            }
            return Response.redirect("/administration/utilisateur/" + id + "?ok=reprise");
        } catch (ValidationException exception) {
            Map<String, String> values = new java.util.HashMap<>(form);
            values.remove("password");
            return Response.invalid(householdsPage(request, values, Notice.error(exception.getMessage())));
        }
    }

    /** Compte actif choisi dans une liste ({@code compte} : son identifiant). */
    private User chosenUser(String raw) {
        try {
            return accounts.user(Long.parseLong(raw.trim())).filter(User::active)
                    .orElseThrow(() -> new ValidationException("Choisissez un compte actif."));
        } catch (NumberFormatException exception) {
            throw new ValidationException("Choisissez un compte.");
        }
    }

    /** Comptes gestionnaires d'un foyer (un seul en principe). */
    private List<User> managers(Household household) {
        return accounts.accessesTo(household.id()).entrySet().stream().filter(entry -> entry.getValue().manager()).map(Map.Entry::getKey).toList();
    }

    /** Ce compte n'est plus gestionnaire : il garde ses autres vues ; sans aucune, il n'a plus acces au foyer. */
    private void demote(User user, Household household) {
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
    private void makeManager(User user, Household household) {
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

    private String login(String raw) {
        String login = raw.trim().toLowerCase(Locale.ROOT);
        if (!LOGIN.matcher(login).matches()) {
            throw new ValidationException("Identifiant : de 2 à 40 caractères, lettres minuscules, chiffres, point, tiret ou souligné.");
        }
        if (login.equalsIgnoreCase(adminLogin)) throw new ValidationException("Cet identifiant est celui de l'administrateur.");
        return login;
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
            long id = accounts.createUser(login, name, request.form().raw("password"), LocalDate.now(clock));
            audit("USER", null, id, "Compte « " + login + " » créé (mot de passe provisoire).");
            if (household != null) {
                String[] parts = member.split(":", 2);
                long person = parts[1].equals("nouveau") ? directory.addMember(household, name, false) : Long.parseLong(parts[1]);
                Household target = household(household);
                Access access = new Access(id, household, target.name(), false, Set.of(person), false, false);
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
        return AccountPages.user(user, households, accounts.passwordToChange(user.id()).isPresent(), notice, csrf);
    }

    /** Droits sur un foyer : sans « Accès », l'acces est retire ; sinon les vues cochees (au moins une). */
    private Response saveAccess(Request request) {
        User user = user(request.id());
        Household household = household(request.id("foyer"));
        Access before = accounts.accesses(user.id()).stream().filter(access -> access.householdId() == household.id()).findFirst().orElse(null);
        try {
            Access access = access(user, household, request.form().values());
            // Un seul gestionnaire par foyer : cocher la case le remplace.
            if (access != null && access.manager()) {
                for (User previous : managers(household)) if (previous.id() != user.id()) demote(previous, household);
            }
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

    /** Droits lus dans le formulaire d'acces ; {@code null} si « Accès » est decoche. */
    private Access access(User user, Household household, Map<String, String> form) {
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

    private User user(long id) {
        return accounts.user(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable", "Cet utilisateur n'existe pas."));
    }

    // ---- Mon compte ----

    private fr.patria.ui.Html accountPage(Request request, Notice notice) {
        if (request.viewer().isAdministrator()) return AccountPages.account(request.viewer().name(), adminLogin, true, notice, request.csrf());
        User user = user(request.viewer().userId());
        return AccountPages.account(user.name(), user.login(), false, notice, request.csrf());
    }

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
            if (forced) return Response.redirect(accounts.welcomed(id) ? "/" : "/bienvenue");
            return Response.redirect("/compte?ok=mot-de-passe");
        } catch (ValidationException exception) {
            return Response.invalid(forced ? newPasswordPage(request, Notice.error(exception.getMessage())) : accountPage(request, Notice.error(exception.getMessage())));
        }
    }
}
