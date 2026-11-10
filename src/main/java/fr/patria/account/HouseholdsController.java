package fr.patria.account;

import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Foyers vus par l'administrateur : supervision (membres, comptes, gestionnaires), depannage d'un foyer sans
 * gestionnaire, invitation a creer un foyer, suppression et bases mises de cote. Les membres et les droits se reglent
 * dans chaque foyer, par ses gestionnaires.
 */
public final class HouseholdsController extends AccountSupport {
    public HouseholdsController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.get("/administration/foyers", request -> {
            // Premier lancement : le type d'installation d'abord.
            if (accounts.installation().mode().isEmpty() && accounts.users().isEmpty()) return Response.redirect("/administration/installation");
            if (InstallationController.blocked(accounts, twoFactors)) return Response.redirect("/compte/double-authentification");
            return Response.page(page(request, request.confirmation(), null));
        });
        router.get("/administration/foyer/{id}", request -> InstallationController.blocked(accounts, twoFactors) ? Response.redirect("/compte/double-authentification")
                : Response.page(detail(request, household(request.id()), request.confirmation(), null)));
        router.post("/administration/foyers/lien", request -> {
            try {
                String email = Mails.address(request.form().raw("email"));
                return invitation(request, null, email);
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage()), null));
            }
        });
        router.post("/administration/foyers/lien/{id}/annuler", request -> {
            accounts.cancelInvitation(request.id(), null);
            return Response.redirect("/administration/foyers?ok=supprime");
        });
        router.post("/administration/foyers/{id}/lien", request -> {
            Household household = unmanaged(request.id());
            String token = token(request, household, "");
            return Response.page(detail(request, household, Notice.success("Transmettez ce lien (valable " + HouseholdController.INVITATION_DAYS + " jours, une seule fois) : "
                    + "la personne reprend la gestion du foyer."), link(request, "/invitation?jeton=" + token)));
        });
        router.post("/administration/foyers/{id}/gestionnaire", request -> {
            Household household = unmanaged(request.id());
            Map.Entry<User, Access> chosen = accounts.accessesTo(household.id()).entrySet().stream()
                    .filter(entry -> String.valueOf(entry.getKey().id()).equals(request.form().raw("compte"))).findFirst()
                    .orElseThrow(() -> new ValidationException("Choisissez un compte qui a accès à ce foyer."));
            Access access = chosen.getValue();
            accounts.setAccess(chosen.getKey().id(), household.id(), new Access(access.userId(), household.id(), household.name(), access.wholeHousehold(),
                    access.members(), access.realEstate(), true));
            audit("ACCESS", household.id(), chosen.getKey().id(), chosen.getKey().name() + " : nommé gestionnaire du foyer « " + household.name() + " ».");
            return Response.redirect("/administration/foyer/" + household.id() + "?ok=acces");
        });
        router.post("/administration/foyers/{id}/supprimer", this::deleteHousehold);
        router.post("/administration/foyers/archives/effacer", request -> {
            directory.purge(request.form().raw("archive"));
            audit("HOUSEHOLD", null, null, "Base d'un foyer supprimé effacée définitivement.");
            return Response.redirect("/administration/foyers?ok=supprime");
        });
    }

    /**
     * Lien d'invitation de l'administrateur : creer son foyer ({@code household} nul), ou reprendre un foyer sans
     * gestionnaire (la personne en devient gestionnaire et voit tout).
     */
    private Response invitation(Request request, Household household, String email) {
        String token = token(request, household, email);
        String link = link(request, "/invitation?jeton=" + token);
        boolean sent = !email.isEmpty() && accounts.mail().active();
        return Response.page(page(request, Notice.success((sent ? "Lien envoyé à " + email + ". " : "") + "Transmettez ce lien (valable "
                + HouseholdController.INVITATION_DAYS + " jours, une seule fois)."), link));
    }

    /** Cree le lien (et l'envoie par e-mail si l'envoi est regle et l'adresse donnee) ; renvoie le jeton. */
    private String token(Request request, Household household, String email) {
        LocalDateTime now = LocalDateTime.now(clock);
        String token = accounts.createInvitation(new AccountRepository.Invitation(0, household == null ? null : household.id(), null, null, true, true, true,
                email.isEmpty() ? null : email, ADMIN, null), now, HouseholdController.INVITATION_DAYS);
        AccountRepository.Mail mail = accounts.mail();
        if (!email.isEmpty() && mail.active()) {
            Mails.send(mail, email, "Patria : créez votre foyer", "Vous êtes invité à créer votre compte et votre foyer sur Patria.\n\nOuvrez ce lien, valable "
                    + HouseholdController.INVITATION_DAYS + " jours :\n" + link(request, "/invitation?jeton=" + token));
        }
        audit("ACCESS", household == null ? null : household.id(), null, household == null ? "Lien « créer son foyer » créé." : "Lien de gestion du foyer « "
                + household.name() + " » créé.");
        return token;
    }

    /**
     * Depannage reserve aux foyers sans gestionnaire actif : l'administrateur ne peut pas s'ouvrir un foyer que ses
     * gestionnaires reglent eux-memes.
     */
    private Household unmanaged(long id) {
        Household household = household(id);
        boolean managed = accounts.accessesTo(household.id()).entrySet().stream().anyMatch(entry -> entry.getKey().active() && entry.getValue().manager());
        if (managed) throw new ValidationException("Ce foyer a un gestionnaire : c'est lui qui invite et règle les droits.");
        return household;
    }

    /** Liste des foyers : recherche (foyer ou compte), filtre, pages de 50, tuiles ; aucune base de foyer n'est ouverte. */
    private fr.patria.ui.Html page(Request request, Notice notice, String link) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<AdminPages.HouseholdLine> all = lines();
        String query = normalize(request.query("q"));
        String filter = java.util.Objects.requireNonNullElse(request.query("filtre"), "");
        List<AdminPages.HouseholdLine> found = all.stream().filter(line -> query.isEmpty() || normalize(line.household().name() + " "
                        + line.accesses().keySet().stream().map(user -> user.name() + " " + user.login()).reduce("", (a, b) -> a + " " + b)).contains(query))
                .filter(line -> switch (filter) {
                    case "sansgestionnaire" -> !line.managed();
                    case "sanscompte" -> line.accesses().keySet().stream().noneMatch(User::active);
                    default -> true;
                }).toList();
        AdminPages.Listing listing = listing(request, query.isEmpty() ? "" : request.query("q").strip(), filter, "", found.size());
        List<AdminPages.HouseholdLine> page = found.stream().skip((long) (listing.page() - 1) * AdminPages.Listing.SIZE).limit(AdminPages.Listing.SIZE).toList();
        boolean recommend = !twoFactors.twoFactor(null).enabled() && Exposure.exposed(request, log);
        int unmanaged = (int) all.stream().filter(line -> !line.managed()).count();
        int created = (int) all.stream().filter(line -> line.createdOn() != null && java.time.YearMonth.from(line.createdOn()).equals(java.time.YearMonth.from(now))).count();
        return AdminPages.households(page, listing, all.size(), unmanaged, created, accounts.pendingInvitations(null, now), directory.archives(), recommend, link, notice,
                request.csrf());
    }

    /** Foyers et comptes qui y ont acces, en trois requetes. */
    private List<AdminPages.HouseholdLine> lines() {
        Map<Long, java.time.LocalDate> created = accounts.householdCreations();
        Map<Long, User> users = new java.util.HashMap<>();
        accounts.users().forEach(user -> users.put(user.id(), user));
        Map<Long, Map<User, Access>> byHousehold = new java.util.HashMap<>();
        accounts.allAccesses().forEach((userId, list) -> list.forEach(access -> {
            User user = users.get(userId);
            if (user != null) byHousehold.computeIfAbsent(access.householdId(), key -> new java.util.LinkedHashMap<>()).put(user, access);
        }));
        return accounts.households().stream().map(household -> new AdminPages.HouseholdLine(household, created.get(household.id()),
                byHousehold.getOrDefault(household.id(), Map.of()))).toList();
    }

    private fr.patria.ui.Html detail(Request request, Household household, Notice notice, String link) {
        AdminPages.HouseholdLine line = new AdminPages.HouseholdLine(household, accounts.householdCreations().get(household.id()), accounts.accessesTo(household.id()));
        return AdminPages.household(line, directory.members(household.id()), accounts.households().size(), link, notice, request.csrf());
    }

    private Response deleteHousehold(Request request) {
        Household household = household(request.id());
        if (accounts.households().size() < 2) throw new ValidationException("C'est le seul foyer de l'installation : il faut en garder au moins un.");
        accounts.deleteHousehold(household.id());
        directory.deleted(household);
        audit("HOUSEHOLD", household.id(), null, "Foyer « " + household.name() + " » supprimé (base mise de côté).");
        return Response.redirect("/administration/foyers?ok=supprime");
    }
}
