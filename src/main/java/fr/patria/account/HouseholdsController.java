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
        router.get("/administration/foyers", request -> Response.page(page(request, request.confirmation(), null)));
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
            return invitation(request, household, "");
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
            return Response.redirect("/administration/foyers?ok=acces#foyer-" + household.id());
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
        LocalDateTime now = LocalDateTime.now(clock);
        String token = accounts.createInvitation(new AccountRepository.Invitation(0, household == null ? null : household.id(), null, null, true, true, true,
                email.isEmpty() ? null : email, ADMIN, null), now, HouseholdController.INVITATION_DAYS);
        String link = link(request, "/invitation?jeton=" + token);
        AccountRepository.Mail mail = accounts.mail();
        boolean sent = !email.isEmpty() && mail.active();
        if (sent) {
            Mails.send(mail, email, "Patria : créez votre foyer", "Vous êtes invité à créer votre compte et votre foyer sur Patria.\n\nOuvrez ce lien, valable "
                    + HouseholdController.INVITATION_DAYS + " jours :\n" + link);
        }
        audit("ACCESS", household == null ? null : household.id(), null, household == null ? "Lien « créer son foyer » créé." : "Lien de gestion du foyer « "
                + household.name() + " » créé.");
        return Response.page(page(request, Notice.success((sent ? "Lien envoyé à " + email + ". " : "") + "Transmettez ce lien (valable "
                + HouseholdController.INVITATION_DAYS + " jours, une seule fois)."), link));
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

    private fr.patria.ui.Html page(Request request, Notice notice, String link) {
        List<AdminPages.HouseholdRow> rows = accounts.households().stream().map(household -> new AdminPages.HouseholdRow(household,
                directory.members(household.id()), accounts.accessesTo(household.id()))).toList();
        boolean recommend = !twoFactors.twoFactor(null).enabled() && Exposure.exposed(request, log);
        return AdminPages.households(rows, accounts.pendingInvitations(null, LocalDateTime.now(clock)), directory.archives(), recommend, link, notice, request.csrf());
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
}
