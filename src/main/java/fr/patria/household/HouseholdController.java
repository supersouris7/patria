package fr.patria.household;

import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.LocalPath;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Administration des membres du foyer (administrateur) et de leurs revenus (utilisateurs, selon leurs vues). */
public final class HouseholdController {
    /** Comptes de connexion lies a chaque membre d'un foyer : identifiants par membre, fournis par l'assemblage. */
    @FunctionalInterface
    public interface Logins {
        Map<Long, List<String>> byMember(long householdId);
    }

    private final HouseholdRepository household;
    private final Clock clock;
    private final Logins logins;

    public HouseholdController(HouseholdRepository household, Clock clock, Logins logins) {
        this.household = household;
        this.clock = clock;
        this.logins = logins;
    }

    public void register(Router router) {
        // Ancienne page « Foyer et revenus », scindee en Membres et Revenus.
        router.userGet("/administration/foyer", request -> Response.redirect(request.viewer().isAdmin() ? "/administration/membres" : "/administration/revenus"));
        router.get("/administration/membres", request -> Response.page(membersPage(request, request.confirmation())));
        router.post("/administration/membres", this::addPerson);
        router.post("/personne/{id}/supprimer", request -> {
            household.deletePerson(request.id());
            return Response.redirect("/administration/membres?ok=supprime");
        });
        router.userGet("/administration/revenus", this::page);
        router.userPost("/administration/revenus", this::addIncome);
        router.userGet("/revenu/{id}", this::editForm);
        router.userPost("/revenu/{id}", this::update);
        router.userPost("/revenu/{id}/supprimer", request -> {
            household.deleteIncome(require(request).id());
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/revenus?ok=supprime");
        });
    }

    private Visibility visibility(Request request) {
        return Visibility.of(request.viewer(), household.people());
    }

    private fr.patria.ui.Html membersPage(Request request, Notice notice) {
        return HouseholdPages.members(household.people(), household.incomes(), logins.byMember(request.viewer().household().id()),
                request.viewer().household().id(), notice, request.csrf());
    }

    private Response page(Request request) {
        return Response.page(HouseholdPages.incomes(visibility(request), household.incomes(), Map.of("start", LocalDate.now(clock).withDayOfMonth(1).toString()),
                request.confirmation(), request.csrf()));
    }

    private Response addPerson(Request request) {
        try {
            String kind = request.form().raw("kind");
            household.addMember(request.form().requiredText("name", "Nom", 60), kind.isBlank() ? Person.Kind.PERSON : Person.Kind.parse(kind));
            return Response.redirect("/administration/membres?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(membersPage(request, Notice.error(exception.getMessage())));
        }
    }

    private Response addIncome(Request request) {
        try {
            household.addIncome(read(request.form(), 0, visibility(request).people()));
            return Response.redirect("/administration/revenus?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.incomes(visibility(request), household.incomes(), request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response editForm(Request request) {
        Income income = visible(request);
        Visibility visibility = visibility(request);
        boolean readOnly = !visibility.sees(income.personId());
        return Response.page(HouseholdPages.edit(income, readOnly ? visibility.everyone() : visibility.people(), HouseholdPages.values(income), null, back(request),
                readOnly, request.csrf()));
    }

    private Response update(Request request) {
        Income current = require(request);
        try {
            household.updateIncome(read(request.form(), current.id(), visibility(request).people()));
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/revenus?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.edit(current, visibility(request).people(), request.form().values(), Notice.error(exception.getMessage()), back(request), false, request.csrf()));
        }
    }

    /** Retour au budget quand le revenu y a ete ouvert ({@code ?retour=/budget...}). */
    private static String back(Request request) {
        String back = LocalPath.safe(request.query("retour"));
        return back != null && back.startsWith("/budget") ? back : null;
    }

    private static Income read(FormData form, long id, List<Person> people) {
        long personId;
        try {
            personId = Long.parseLong(form.raw("person"));
        } catch (NumberFormatException exception) {
            throw new ValidationException("Personne invalide.");
        }
        if (people.stream().noneMatch(person -> person.id() == personId)) throw new ValidationException("Personne invalide.");
        Frequency frequency = Frequency.parse(form.raw("frequency"));
        if (!HouseholdPages.INCOME_FREQUENCIES.contains(frequency)) throw new ValidationException("Fréquence invalide.");
        LocalDate start = form.date("start", "Premier versement");
        LocalDate end = form.raw("end").isBlank() ? null : form.date("end", "Fin");
        if (end != null && end.isBefore(start)) throw new ValidationException("La fin ne peut pas précéder le premier versement.");
        return new Income(id, personId, form.requiredText("label", "Libellé", 100), IncomeKind.parse(form.raw("kind")),
                form.positiveAmount("amount", "Montant"), frequency, start, end);
    }

    /** Revenu que la personne connectee peut modifier : celui d'un membre dont la vue est ouverte. */
    private Income require(Request request) {
        Income income = visible(request);
        visibility(request).requireEdit(visibility(request).sees(income.personId()));
        return income;
    }

    /** Revenu visible : d'un membre ouvert, ou tous avec la vue « Foyer ». */
    private Income visible(Request request) {
        Income income = household.income(request.id()).orElseThrow(() -> new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas."));
        if (!visibility(request).canSeeAny(income.personId())) throw new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas.");
        return income;
    }
}
