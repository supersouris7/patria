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

/** Page Foyers de l'administrateur : foyers, membres (noms), gestionnaire, avancement, reprise, foyers supprimes. */
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

    }

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
}
