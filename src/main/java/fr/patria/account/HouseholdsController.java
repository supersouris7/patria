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

/** Page Foyers de l'administrateur : foyers, membres (noms), tableau des droits, avancement, reprise, foyers supprimes. */
public final class HouseholdsController extends AccountSupport {
    public HouseholdsController(AccountSupport.Stores stores) {
        super(stores);
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
        router.post("/administration/foyers/{id}/membre/{membre}/supprimer", request -> {
            Household household = household(request.id());
            long id = request.id("membre");
            HouseholdDirectory.Member member = directory.members(household.id()).stream().filter(candidate -> candidate.id() == id).findFirst()
                    .orElseThrow(() -> new fr.patria.common.NotFoundException("Membre introuvable", "Ce membre n'existe pas."));
            try {
                directory.deleteMember(household.id(), id);
            } catch (ValidationException exception) {
                return Response.invalid(householdsPage(request, Map.of(), Notice.error(exception.getMessage())));
            }
            accounts.forgetMember(household.id(), id);
            audit("MEMBER", household.id(), null, "Membre « " + member.name() + " » supprimé du foyer « " + household.name() + " ».");
            return Response.redirect("/administration/foyers?ok=supprime#foyer-" + household.id());
        });
        // Tableau des droits : une ligne par compte actif, une case par membre, totaux et immobilier.
        router.post("/administration/foyers/{id}/droits", request -> {
            Household household = household(request.id());
            Map<String, String> form = request.form().values();
            Map<User, Access> before = accounts.accessesTo(household.id());
            List<HouseholdDirectory.Member> members = directory.members(household.id());
            for (User user : accounts.users()) {
                if (!user.active()) continue;
                Access after = access(user, household, form, "u" + user.id() + "_");
                Access previous = before.get(user);
                if (same(previous, after)) continue;
                accounts.setAccess(user.id(), household.id(), after);
                audit("ACCESS", household.id(), user.id(), user.name() + " : " + (after.any() ? (previous == null ? "accès ouvert" : "accès modifié") + " ("
                        + AudienceText.summary(after, members) + ")" : "plus d'accès à ce foyer") + ".");
            }
            return Response.redirect("/administration/foyers?ok=acces#foyer-" + household.id());
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

    }

    private fr.patria.ui.Html householdsPage(Request request, Map<String, String> values, Notice notice) {
        List<User> users = accounts.users();
        List<AccountPages.HouseholdCard> cards = accounts.households().stream().map(household -> {
            Map<User, Access> shared = accounts.accessesTo(household.id());
            List<AccountPages.AccountProgress> progress = shared.entrySet().stream().filter(entry -> entry.getKey().active())
                    .map(entry -> new AccountPages.AccountProgress(entry.getKey(),
                            directory.progress(household.id(), entry.getKey().id(), entry.getValue().realEstate()))).toList();
            return new AccountPages.HouseholdCard(household, directory.members(household.id()), shared, progress);
        }).toList();
        List<Household> orphans = cards.stream().filter(card -> card.accesses().keySet().stream().noneMatch(User::active))
                .map(AccountPages.HouseholdCard::household).toList();
        boolean recommend = !twoFactors.twoFactor(null).enabled() && Exposure.exposed(request, log);
        return AccountPages.households(cards, orphans, users.stream().filter(User::active).toList(), recommend, directory.archives(), values, notice, request.csrf());
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
     * Reprise des foyers auxquels aucun compte n'a acces : un compte recoit tous les droits sur les foyers coches,
     * avec la progression du guide de l'administrateur. Sert au passage a la version ou
     * l'administrateur ne voit plus les finances.
     */
    private Response takeOver(Request request) {
        Map<String, String> form = request.form().values();
        try {
            List<Household> chosen = accounts.households().stream().filter(household -> "on".equals(form.get("foyer_" + household.id()))).toList();
            if (chosen.isEmpty()) throw new ValidationException("Cochez au moins un foyer à confier à ce compte.");
            if (!form.getOrDefault("compte", "").isBlank()) {
                // Compte existant : tous les droits sur les foyers coches.
                User user = chosenUser(form.get("compte"));
                for (Household household : chosen) grantAll(user, household);
                return Response.redirect("/administration/foyers?ok=acces");
            }
            String login = login(form.getOrDefault("login", ""));
            String name = request.form().requiredText("name", "Nom affiché", 60);
            long id = accounts.createUser(login, name, form.getOrDefault("password", ""), LocalDate.now(clock));
            audit("USER", null, id, "Compte « " + login + " » créé pour reprendre des foyers.");
            User user = accounts.user(id).orElseThrow();
            for (Household household : chosen) grantAll(user, household);
            return Response.redirect("/administration/utilisateur/" + id + "?ok=reprise");
        } catch (ValidationException exception) {
            Map<String, String> values = new java.util.HashMap<>(form);
            values.remove("password");
            return Response.invalid(householdsPage(request, values, Notice.error(exception.getMessage())));
        }
    }

    /** Tous les droits sur un foyer (reprise), avec la progression du guide de l'administrateur. */
    private void grantAll(User user, Household household) {
        Access access = access(user, household, Map.of("all", "on"), "");
        accounts.setAccess(user.id(), household.id(), access);
        directory.transferFromAdministrator(household.id(), user.id());
        audit("ACCESS", household.id(), user.id(), user.name() + " : accès ouvert (" + AudienceText.summary(access, directory.members(household.id())) + ").");
    }

    private static boolean same(Access before, Access after) {
        if (before == null) return !after.any();
        return before.wholeHousehold() == after.wholeHousehold() && before.realEstate() == after.realEstate() && before.members().equals(after.members());
    }
}
