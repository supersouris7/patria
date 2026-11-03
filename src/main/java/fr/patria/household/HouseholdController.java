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

/** Administration des personnes du foyer et de leurs revenus. */
public final class HouseholdController {
    private final HouseholdRepository household;
    private final Clock clock;

    public HouseholdController(HouseholdRepository household, Clock clock) {
        this.household = household;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/foyer", this::page);
        router.post("/administration/foyer/personnes", this::addPerson);
        router.post("/personne/{id}/supprimer", request -> {
            household.deletePerson(request.id());
            return Response.redirect("/administration/foyer?ok=supprime");
        });
        router.post("/administration/foyer/revenus", this::addIncome);
        router.get("/revenu/{id}", this::editForm);
        router.post("/revenu/{id}", this::update);
        router.post("/revenu/{id}/supprimer", request -> {
            household.deleteIncome(require(request.id()).id());
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/foyer?ok=supprime");
        });
    }

    private Response page(Request request) {
        return Response.page(HouseholdPages.page(household.people(), household.incomes(), Map.of("start", LocalDate.now(clock).withDayOfMonth(1).toString()),
                request.confirmation(), request.csrf()));
    }

    private Response addPerson(Request request) {
        try {
            household.addPerson(request.form().requiredText("name", "Nom", 60));
            return Response.redirect("/administration/foyer?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.page(household.people(), household.incomes(), Map.of(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response addIncome(Request request) {
        try {
            household.addIncome(read(request.form(), 0, household.people()));
            return Response.redirect("/administration/foyer?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.page(household.people(), household.incomes(), request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response editForm(Request request) {
        Income income = require(request.id());
        return Response.page(HouseholdPages.edit(income, household.people(), HouseholdPages.values(income), null, back(request), request.csrf()));
    }

    private Response update(Request request) {
        Income current = require(request.id());
        try {
            household.updateIncome(read(request.form(), current.id(), household.people()));
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/foyer?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(HouseholdPages.edit(current, household.people(), request.form().values(), Notice.error(exception.getMessage()), back(request), request.csrf()));
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

    private Income require(long id) {
        return household.income(id).orElseThrow(() -> new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas."));
    }
}
