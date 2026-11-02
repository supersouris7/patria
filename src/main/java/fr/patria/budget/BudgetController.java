package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/** Accueil (budget du mois et des 12 mois a venir), budget moyen et saisie des depenses. */
public final class BudgetController {
    private final Forecast forecast;
    private final BudgetRepository items;
    private final Clock clock;

    public BudgetController(Forecast forecast, BudgetRepository items, Clock clock) {
        this.forecast = forecast;
        this.items = items;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/", request -> Response.page(BudgetPages.home(forecast.next12Months(), request.csrf())));
        router.get("/budget", request -> {
            Forecast.View view = forecast.next12Months();
            return Response.page(BudgetPages.budget(view, forecast.averageIncomes(view), forecast.averageExpenses(view), request.csrf()));
        });
        router.get("/administration", request -> Response.redirect("/administration/foyer"));
        router.get("/administration/depenses", request -> Response.page(BudgetPages.admin(items.all(), defaults(), request.confirmation(), request.csrf())));
        router.post("/administration/depenses", this::create);
        router.get("/depense/{id}", request -> {
            BudgetItem item = require(request.id());
            return Response.page(BudgetPages.edit(item, BudgetPages.values(item), null, request.csrf()));
        });
        router.post("/depense/{id}", this::update);
        router.post("/depense/{id}/supprimer", request -> {
            items.delete(require(request.id()).id());
            return Response.redirect("/administration/depenses?ok=supprime");
        });
    }

    private Map<String, String> defaults() {
        return Map.of("start", LocalDate.now(clock).withDayOfMonth(1).toString());
    }

    private Response create(Request request) {
        try {
            items.insert(read(request.form(), 0));
            return Response.redirect("/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(BudgetPages.admin(items.all(), request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response update(Request request) {
        BudgetItem current = require(request.id());
        try {
            items.update(read(request.form(), current.id()));
            return Response.redirect("/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(BudgetPages.edit(current, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private static BudgetItem read(FormData form, long id) {
        LocalDate start = form.date("start", "Premier paiement");
        LocalDate end = form.raw("end").isBlank() ? null : form.date("end", "Dernier paiement");
        if (end != null && end.isBefore(start)) throw new ValidationException("Le dernier paiement ne peut pas précéder le premier.");
        return new BudgetItem(id, form.requiredText("label", "Libellé", 100), Category.parse(form.raw("category")), form.positiveAmount("amount", "Montant"),
                Frequency.parse(form.raw("frequency")), start, end);
    }

    private BudgetItem require(long id) {
        return items.find(id).orElseThrow(() -> new NotFoundException("Dépense introuvable", "Cette dépense n'existe pas."));
    }
}
