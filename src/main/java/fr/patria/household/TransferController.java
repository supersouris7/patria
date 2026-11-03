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

/** Administration des virements entre membres du foyer (vers le compte joint...), recurrents ou ponctuels. */
public final class TransferController {
    private final HouseholdRepository household;
    private final Clock clock;

    public TransferController(HouseholdRepository household, Clock clock) {
        this.household = household;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/virements", request -> Response.page(page(defaults(), request.confirmation(), request.csrf())));
        router.post("/administration/virements", this::create);
        router.get("/virement/{id}", request -> {
            Transfer transfer = require(request.id());
            return Response.page(TransferPages.edit(transfer, household.people(), TransferPages.values(transfer), null, back(request), request.csrf()));
        });
        router.post("/virement/{id}", this::update);
        router.post("/virement/{id}/supprimer", request -> {
            household.deleteTransfer(require(request.id()).id());
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/virements?ok=supprime");
        });
    }

    private fr.patria.ui.Html page(Map<String, String> values, Notice notice, String csrf) {
        return TransferPages.page(household.people(), household.transfers(), values, notice, csrf);
    }

    private Map<String, String> defaults() {
        List<Person> people = household.people();
        Map<String, String> values = new java.util.HashMap<>();
        values.put("start", LocalDate.now(clock).withDayOfMonth(1).toString());
        people.stream().filter(person -> !person.isAccount()).findFirst().ifPresent(person -> values.put("from", String.valueOf(person.id())));
        people.stream().filter(Person::isAccount).findFirst().ifPresent(account -> values.put("to", String.valueOf(account.id())));
        return values;
    }

    private Response create(Request request) {
        try {
            household.addTransfer(read(request.form(), 0, household.people()));
            return Response.redirect("/administration/virements?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(page(request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response update(Request request) {
        Transfer current = require(request.id());
        try {
            household.updateTransfer(read(request.form(), current.id(), household.people()));
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/virements?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(TransferPages.edit(current, household.people(), request.form().values(), Notice.error(exception.getMessage()), back(request), request.csrf()));
        }
    }

    static Transfer read(FormData form, long id, List<Person> people) {
        long from = member(form.raw("from"), people, "Émetteur");
        long to = member(form.raw("to"), people, "Destinataire");
        if (from == to) throw new ValidationException("L'émetteur et le destinataire doivent être différents.");
        Frequency frequency = Frequency.parse(form.raw("frequency"));
        LocalDate start = form.date("start", frequency == Frequency.ONCE ? "Date du virement" : "Premier virement");
        LocalDate end = frequency == Frequency.ONCE || form.raw("end").isBlank() ? null : form.date("end", "Dernier virement");
        if (end != null && end.isBefore(start)) throw new ValidationException("Le dernier virement ne peut pas précéder le premier.");
        return new Transfer(id, from, to, form.optionalText("label", "Libellé", 100), form.positiveAmount("amount", "Montant"), frequency, start, end);
    }

    private static long member(String raw, List<Person> people, String label) {
        try {
            long id = Long.parseLong(raw);
            if (people.stream().anyMatch(person -> person.id() == id)) return id;
        } catch (NumberFormatException ignored) {
            // message commun ci-dessous
        }
        throw new ValidationException(label + " invalide.");
    }

    /** Retour au budget quand le virement y a ete ouvert ({@code ?retour=/budget...}). */
    private static String back(Request request) {
        String back = LocalPath.safe(request.query("retour"));
        return back != null && back.startsWith("/budget") ? back : null;
    }

    private Transfer require(long id) {
        return household.transfer(id).orElseThrow(() -> new NotFoundException("Virement introuvable", "Ce virement n'existe pas."));
    }
}
