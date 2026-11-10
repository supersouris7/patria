package fr.patria.account;

import fr.patria.common.ForbiddenException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * « Mon foyer », pour ses gestionnaires : membres du budget, tableau des droits (qui voit quoi, qui gere), invitations,
 * nom du foyer. Un gestionnaire regle aussi ses propres cases : il peut choisir de ne pas voir tout le foyer. Il reste
 * toujours au moins un gestionnaire.
 */
public final class HouseholdController extends AccountSupport {
    static final int INVITATION_DAYS = 7;

    public HouseholdController(AccountSupport.Stores stores) {
        super(stores);
    }

    public void register(Router router) {
        router.userGet("/foyer", request -> Response.page(page(request, request.confirmation(), null)));
        router.userPost("/foyer/membres", request -> {
            Household household = managed(request);
            try {
                String kind = request.form().raw("kind");
                if (!kind.equals("PERSON") && !kind.equals("ACCOUNT")) throw new ValidationException("Type de membre invalide.");
                String name = request.form().requiredText("name", "Nom du membre", 60);
                long member = directory.addMember(household.id(), name, kind.equals("ACCOUNT"));
                // Le gestionnaire qui l'ajoute le voit ; il peut decocher ensuite.
                Access own = own(request, household);
                Set<Long> members = new LinkedHashSet<>(own.members());
                members.add(member);
                accounts.setAccess(own.userId(), household.id(), new Access(own.userId(), household.id(), household.name(), own.wholeHousehold(), members,
                        own.realEstate(), true));
                audit(request.viewer().name(), "MEMBER", household.id(), null, "Membre « " + name + " » ajouté au foyer « " + household.name() + " ».");
                return Response.redirect("/foyer?ok=enregistre#membres");
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage()), null));
            }
        });
        router.userPost("/foyer/membre/{membre}/supprimer", request -> {
            Household household = managed(request);
            long id = request.id("membre");
            HouseholdDirectory.Member member = directory.members(household.id()).stream().filter(candidate -> candidate.id() == id).findFirst()
                    .orElseThrow(() -> new fr.patria.common.NotFoundException("Membre introuvable", "Ce membre n'existe pas."));
            try {
                directory.deleteMember(household.id(), id);
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage()), null));
            }
            accounts.forgetMember(household.id(), id);
            audit(request.viewer().name(), "MEMBER", household.id(), null, "Membre « " + member.name() + " » supprimé du foyer « " + household.name() + " ».");
            return Response.redirect("/foyer?ok=supprime#membres");
        });
        router.userPost("/foyer/droits", this::saveRights);
        router.userPost("/foyer/invitations", this::invite);
        router.userPost("/foyer/invitation/{id}/annuler", request -> {
            Household household = managed(request);
            accounts.cancelInvitation(request.id(), household.id());
            return Response.redirect("/foyer?ok=supprime#invitations");
        });
        router.userPost("/foyer/supprimer", request -> {
            Household household = managed(request);
            if (!request.form().raw("confirmation").strip().equals(household.name())) {
                return Response.invalid(page(request, Notice.error("Pour supprimer le foyer, tapez exactement son nom : « " + household.name() + " »."), null));
            }
            if (accounts.households().size() < 2) {
                return Response.invalid(page(request, Notice.error("C'est le seul foyer de cette installation : il ne peut pas être supprimé."), null));
            }
            accounts.deleteHousehold(household.id());
            directory.deleted(household);
            audit(request.viewer().name(), "HOUSEHOLD", household.id(), null, "Foyer « " + household.name() + " » supprimé par son gestionnaire (base mise de côté).");
            return Response.redirect("/");
        });
        router.userPost("/foyer/renommer", request -> {
            Household household = managed(request);
            try {
                String name = request.form().requiredText("name", "Nom du foyer", 60);
                accounts.renameHousehold(household.id(), name);
                audit(request.viewer().name(), "HOUSEHOLD", household.id(), null, "Foyer « " + household.name() + " » renommé en « " + name + " ».");
                return Response.redirect("/foyer?ok=enregistre");
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage()), null));
            }
        });
    }

    /** Foyer affiche, si la personne connectee en est gestionnaire. */
    private Household managed(Request request) {
        if (request.viewer().isAdministrator() || !request.viewer().householdManager()) {
            throw new ForbiddenException("Seuls les gestionnaires du foyer règlent les membres, les droits et les invitations.");
        }
        return household(request.viewer().household().id());
    }

    private Access own(Request request, Household household) {
        return accounts.accessesTo(household.id()).entrySet().stream().filter(entry -> entry.getKey().id() == request.viewer().userId())
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }

    /** Tableau des droits : une ligne par compte du foyer ; cases des membres, totaux, immobilier, gestionnaire. */
    private Response saveRights(Request request) {
        Household household = managed(request);
        Map<String, String> form = request.form().values();
        Map<User, Access> before = accounts.accessesTo(household.id());
        List<HouseholdDirectory.Member> members = directory.members(household.id());
        Map<User, Access> after = new java.util.LinkedHashMap<>();
        for (User user : before.keySet()) {
            String prefix = "u" + user.id() + "_";
            Access access = access(user, household, form, prefix);
            after.put(user, new Access(user.id(), household.id(), household.name(), access.wholeHousehold(), access.members(), access.realEstate(),
                    "on".equals(form.get(prefix + "manager"))));
        }
        if (after.values().stream().noneMatch(Access::manager)) {
            return Response.invalid(page(request, Notice.error("Gardez au moins un gestionnaire : sinon plus personne ne pourrait régler ce foyer."), null));
        }
        after.forEach((user, access) -> {
            Access previous = before.get(user);
            if (same(previous, access)) return;
            accounts.setAccess(user.id(), household.id(), access);
            audit(request.viewer().name(), "ACCESS", household.id(), user.id(), user.name() + " : " + (access.any() || access.manager()
                    ? "accès modifié (" + AudienceText.summary(access, members) + (access.manager() ? ", gestionnaire" : "") + ")" : "plus d'accès à ce foyer") + ".");
        });
        return Response.redirect("/foyer?ok=acces#droits");
    }

    /** Invitation : lien a transmettre (et e-mail si l'envoi est regle), droits choisis par le gestionnaire. */
    private Response invite(Request request) {
        Household household = managed(request);
        if ("closed".equals(accounts.signupMode())) throw new ValidationException("Les nouveaux comptes sont fermés sur ce Patria (réglage de l'administrateur).");
        Map<String, String> form = request.form().values();
        try {
            String email = Mails.address(form.getOrDefault("email", ""));
            String choice = form.getOrDefault("membre", "nouveau");
            Long memberId = null;
            String memberName = null;
            if (choice.equals("nouveau")) {
                memberName = request.form().requiredText("name", "Prénom de la personne invitée", 60);
            } else if (!choice.equals("aucun")) {
                long id = Long.parseLong(choice);
                if (directory.members(household.id()).stream().noneMatch(member -> member.id() == id)) throw new ValidationException("Ce membre n'existe plus.");
                memberId = id;
            }
            if (choice.equals("aucun") && !"on".equals(form.get("whole")) && !"on".equals(form.get("estate")) && !"on".equals(form.get("manager"))) {
                throw new ValidationException("Sans membre, cochez au moins une case : sinon la personne n'aurait accès à rien.");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            AccountRepository.Invitation invitation = new AccountRepository.Invitation(0, household.id(), memberId, memberName, "on".equals(form.get("whole")),
                    "on".equals(form.get("estate")), "on".equals(form.get("manager")), email.isEmpty() ? null : email, request.viewer().name(), null);
            String token = accounts.createInvitation(invitation, now, INVITATION_DAYS);
            String link = link(request, "/invitation?jeton=" + token);
            AccountRepository.Mail mail = accounts.mail();
            boolean sent = false;
            if (!email.isEmpty() && mail.active()) {
                Mails.send(mail, email, "Patria : invitation au foyer « " + household.name() + " »", request.viewer().name() + " vous invite à rejoindre le foyer « "
                        + household.name() + " » sur Patria.\n\nPour créer votre compte (ou utiliser le vôtre), ouvrez ce lien, valable " + INVITATION_DAYS + " jours :\n" + link);
                sent = true;
            }
            audit(request.viewer().name(), "ACCESS", household.id(), null, "Invitation créée pour le foyer « " + household.name() + " »"
                    + (memberName != null ? " (" + memberName + ")" : "") + ".");
            return Response.page(page(request, Notice.success(sent ? "Invitation envoyée à " + email + ". Vous pouvez aussi transmettre le lien ci-dessous."
                    : "Invitation créée : transmettez ce lien à la personne (valable " + INVITATION_DAYS + " jours, une seule fois)."), link));
        } catch (NumberFormatException exception) {
            return Response.invalid(page(request, Notice.error("Membre invalide."), null));
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error(exception.getMessage()), null));
        }
    }

    private fr.patria.ui.Html page(Request request, Notice notice, String link) {
        Household household = managed(request);
        return HouseholdPages.page(household, directory.members(household.id()), accounts.accessesTo(household.id()),
                accounts.pendingInvitations(household.id(), LocalDateTime.now(clock)), request.viewer().userId(), "closed".equals(accounts.signupMode()),
                notice, link, request.csrf());
    }

    private static boolean same(Access before, Access after) {
        if (before == null) return !after.any() && !after.manager();
        return before.wholeHousehold() == after.wholeHousehold() && before.realEstate() == after.realEstate() && before.members().equals(after.members())
                && before.manager() == after.manager();
    }
}
