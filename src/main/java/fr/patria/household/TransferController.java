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
        router.userGet("/administration/virements", request -> Response.page(page(request, defaults(request), request.confirmation())));
        router.userPost("/administration/virements", this::create);
        router.userGet("/virement/{id}", request -> {
            Transfer transfer = require(request);
            return Response.page(TransferPages.edit(transfer, household.people(), visibility(request).people(), TransferPages.values(transfer), null, back(request), request.csrf()));
        });
        router.userPost("/virement/{id}", this::update);
        router.userPost("/virement/{id}/supprimer", request -> {
            household.deleteTransfer(require(request).id());
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/virements?ok=supprime");
        });
    }

    private Visibility visibility(Request request) {
        return Visibility.of(request.viewer(), household.people());
    }

    private fr.patria.ui.Html page(Request request, Map<String, String> values, Notice notice) {
        return TransferPages.page(visibility(request), household.transfers(), values, notice, request.csrf());
    }

    private Map<String, String> defaults(Request request) {
        List<Person> people = visibility(request).people();
        Map<String, String> values = new java.util.HashMap<>();
        values.put("start", LocalDate.now(clock).withDayOfMonth(1).toString());
        people.stream().filter(person -> !person.isAccount()).findFirst().ifPresent(person -> values.put("from", String.valueOf(person.id())));
        people.stream().filter(Person::isAccount).findFirst().ifPresent(account -> values.put("to", String.valueOf(account.id())));
        return values;
    }

    private Response create(Request request) {
        try {
            household.addTransfer(read(request.form(), 0, visibility(request).people()));
            return Response.redirect("/administration/virements?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(page(request, request.form().values(), Notice.error(exception.getMessage())));
        }
    }

    private Response update(Request request) {
        Transfer current = require(request);
        try {
            household.updateTransfer(read(request.form(), current.id(), visibility(request).people()));
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/virements?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(TransferPages.edit(current, household.people(), visibility(request).people(), request.form().values(),
                    Notice.error(exception.getMessage()), back(request), request.csrf()));
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

    /** Virement que la personne connectee peut modifier : entre deux membres dont la vue est ouverte. */
    private Transfer require(Request request) {
        Transfer transfer = household.transfer(request.id()).orElseThrow(() -> new NotFoundException("Virement introuvable", "Ce virement n'existe pas."));
        Visibility visibility = visibility(request);
        if (!visibility.canSeeAny(transfer.fromId(), transfer.toId())) throw new NotFoundException("Virement introuvable", "Ce virement n'existe pas.");
        visibility.requireEdit(visibility.canEditAll(transfer.fromId(), transfer.toId()));
        return transfer;
    }
}
