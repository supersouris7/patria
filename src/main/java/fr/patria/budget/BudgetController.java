package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accueil (budget du mois et des 12 mois a venir), budget moyen, saisie des depenses et repartition entre les
 * personnes. {@code ?personne=ID} affiche le budget d'une personne, sans parametre celui du foyer.
 */
public final class BudgetController {
    private final Forecast forecast;
    private final Forecast.Sources sources;
    private final BudgetRepository items;
    private final Clock clock;

    public BudgetController(Forecast forecast, Forecast.Sources sources, Clock clock) {
        this.forecast = forecast;
        this.sources = sources;
        this.items = sources.items();
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/", request -> Response.page(BudgetPages.home(forecast.next12Months(person(request)), request.csrf())));
        router.get("/budget", request -> {
            Forecast.View view = forecast.next12Months(person(request));
            return Response.page(BudgetPages.budget(view, forecast.averageIncomes(view), forecast.averageExpenses(view), request.csrf()));
        });
        router.get("/administration", request -> Response.redirect("/administration/foyer"));
        router.get("/administration/depenses", request -> Response.page(adminPage(defaults(), request.confirmation(), request.csrf())));
        router.post("/administration/depenses", this::create);
        router.get("/depense/{id}", request -> {
            BudgetItem item = require(request.id());
            Map<String, String> values = new HashMap<>(BudgetPages.values(item));
            values.putAll(Shares.values(sources.shares().of(Shares.Subject.BUDGET_ITEM, item.id()), people()));
            return Response.page(BudgetPages.edit(item, people(), values, null, request.csrf()));
        });
        router.post("/depense/{id}", this::update);
        router.post("/depense/{id}/supprimer", request -> {
            long id = require(request.id()).id();
            items.delete(id);
            sources.shares().delete(Shares.Subject.BUDGET_ITEM, id);
            return Response.redirect("/administration/depenses?ok=supprime");
        });
        router.get("/administration/repartition", request -> Response.page(sharesPage(request.confirmation(), request.csrf())));
        router.post("/administration/repartition/bien/{id}", request -> saveShares(request, Shares.Subject.PROPERTY,
                sources.properties().find(request.id()).isPresent()));
        router.post("/administration/repartition/pret/{id}", request -> saveShares(request, Shares.Subject.LOAN,
                sources.loans().find(request.id()).isPresent()));
    }

    /** Personne demandee par {@code ?personne=ID}, {@code null} pour le foyer. */
    private static Long person(Request request) {
        String raw = request.query("personne");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<Person> people() {
        return sources.household().people();
    }

    private fr.patria.ui.Html adminPage(Map<String, String> values, Notice notice, String csrf) {
        return BudgetPages.admin(items.all(), sources.shares().all(), people(), values, notice, csrf);
    }

    private fr.patria.ui.Html sharesPage(Notice notice, String csrf) {
        return BudgetPages.shares(sources.properties().all(), sources.loans().all(), sources.shares().all(), people(), notice, csrf);
    }

    private Response saveShares(Request request, Shares.Subject subject, boolean exists) {
        if (!exists) throw new NotFoundException("Élément introuvable", "Ce bien ou ce prêt n'existe pas.");
        try {
            sources.shares().set(subject, request.id(), Shares.parse(request.form().values(), people()));
            return Response.redirect("/administration/repartition?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(sharesPage(Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Map<String, String> defaults() {
        return Map.of("start", LocalDate.now(clock).withDayOfMonth(1).toString());
    }

    private Response create(Request request) {
        try {
            BudgetItem item = read(request.form(), 0);
            Map<Long, Integer> shares = Shares.parse(request.form().values(), people());
            long id = items.insert(item);
            sources.shares().set(Shares.Subject.BUDGET_ITEM, id, shares);
            return Response.redirect("/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response update(Request request) {
        BudgetItem current = require(request.id());
        try {
            BudgetItem item = read(request.form(), current.id());
            Map<Long, Integer> shares = Shares.parse(request.form().values(), people());
            items.update(item);
            sources.shares().set(Shares.Subject.BUDGET_ITEM, current.id(), shares);
            return Response.redirect("/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(BudgetPages.edit(current, people(), request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
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
