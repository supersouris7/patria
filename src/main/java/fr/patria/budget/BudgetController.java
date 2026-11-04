package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.LocalPath;
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
        router.get("/administration/depenses/modele.csv", request -> new Response.Download("modele-depenses.csv", "text/csv; charset=utf-8",
                ExpenseCsv.template().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        router.post("/administration/depenses/import", this::preview);
        router.post("/administration/depenses/import/valider", this::confirmImport);
        router.get("/depense/{id}", request -> {
            BudgetItem item = require(request.id());
            Map<String, String> values = new HashMap<>(BudgetPages.values(item));
            values.putAll(Shares.values(sources.shares().of(Shares.Subject.BUDGET_ITEM, item.id()), people()));
            return Response.page(BudgetPages.edit(item, people(), values, null, back(request), request.csrf()));
        });
        router.post("/depense/{id}", this::update);
        router.post("/depense/{id}/supprimer", request -> {
            long id = require(request.id()).id();
            items.delete(id);
            sources.shares().delete(Shares.Subject.BUDGET_ITEM, id);
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/depenses?ok=supprime");
        });
        router.get("/administration/repartition", request -> Response.page(sharesPage(request.confirmation(), request.csrf())));
        router.post("/administration/repartition/bien/{id}", request -> saveShares(request, Shares.Subject.PROPERTY,
                sources.properties().find(request.id()).isPresent()));
        router.post("/administration/repartition/bien/{id}/taxe-fonciere", this::saveTaxPayer);
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
        return BudgetPages.admin(items.all(), sources.shares().all(), people(), sources.properties().all(), values, notice, csrf);
    }

    private fr.patria.ui.Html sharesPage(Notice notice, String csrf) {
        return BudgetPages.shares(sources.properties().all(), sources.loans().all(), sources.shares().all(), sources.shares().propertyTaxPayers(), people(), notice, csrf);
    }

    private Response saveShares(Request request, Shares.Subject subject, boolean exists) {
        if (!exists) throw new NotFoundException("Élément introuvable", "Ce bien ou ce prêt n'existe pas.");
        try {
            sources.shares().set(subject, request.id(), Shares.parse(request.form().values(), people()));
            return Response.redirect("/administration/repartition?ok=enregistre");
        } catch (ValidationException exception) {
            BudgetPages.Rejected rejected = new BudgetPages.Rejected(new Shares.Key(subject, request.id()), request.form().values(), exception.getMessage());
            return Response.invalid(BudgetPages.shares(sources.properties().all(), sources.loans().all(), sources.shares().all(), sources.shares().propertyTaxPayers(), people(), null, rejected,
                    request.csrf()));
        }
    }

    /** Membre qui paie la taxe fonciere d'un bien ; vide : comme le bien. */
    private Response saveTaxPayer(Request request) {
        if (sources.properties().find(request.id()).isEmpty()) throw new NotFoundException("Bien introuvable", "Ce bien n'existe pas.");
        String raw = request.form().raw("payer").trim();
        Long payer = null;
        if (!raw.isEmpty()) {
            try {
                payer = Long.parseLong(raw);
            } catch (NumberFormatException exception) {
                throw new ValidationException("Membre invalide.");
            }
            long id = payer;
            if (people().stream().noneMatch(person -> person.id() == id)) throw new ValidationException("Membre invalide.");
        }
        sources.shares().setPropertyTaxPayer(request.id(), payer);
        return Response.redirect("/administration/repartition?ok=enregistre");
    }

    private Map<String, String> defaults() {
        return Map.of("start", LocalDate.now(clock).withDayOfMonth(1).toString());
    }

    private Response preview(Request request) {
        try {
            String content = new String(request.form().requiredFile("csv", "Fichier CSV"), java.nio.charset.StandardCharsets.UTF_8);
            List<ExpenseCsv.Row> rows = ExpenseCsv.parse(content, items.all());
            boolean ok = rows.stream().allMatch(ExpenseCsv.Row::valid);
            return new Response.Page(ok ? 200 : 400, BudgetPages.importPreview(rows, content, people(), Map.of(), request.csrf()));
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(defaults(), Notice.error("Import impossible. " + exception.getMessage()), request.csrf()));
        }
    }

    /** Le contenu est relu et reverifie : l'apercu ne sert qu'a l'affichage. */
    private Response confirmImport(Request request) {
        String content;
        try {
            content = new String(java.util.Base64.getUrlDecoder().decode(request.form().raw("contenu")), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return Response.invalid(adminPage(defaults(), Notice.error("Import impossible : contenu illisible, recommencez depuis le fichier."), request.csrf()));
        }
        try {
            List<ExpenseCsv.Row> rows = ExpenseCsv.parse(content, items.all());
            if (!rows.stream().allMatch(ExpenseCsv.Row::valid)) return Response.invalid(BudgetPages.importPreview(rows, content, people(), Map.of(), request.csrf()));
            List<Person> people = people();
            Map<String, String> form = request.form().values();
            Map<Long, Integer> all = owner(form.getOrDefault("attribution", ""), people, false);
            List<BudgetRepository.NewExpense> fresh = rows.stream().filter(ExpenseCsv.Row::fresh).map(row -> {
                String own = form.getOrDefault("attribution_" + row.line(), "");
                return new BudgetRepository.NewExpense(row.item(), own.isEmpty() ? all : owner(own, people, true));
            }).toList();
            items.insertAll(fresh);
            return Response.redirect("/administration/depenses?ok=importe");
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(defaults(), Notice.error("Import impossible. " + exception.getMessage()), request.csrf()));
        }
    }

    /** Attribution choisie dans l'apercu : vide (ou « egal » sur une ligne) = parts egales, sinon une personne a 100 %. */
    private static Map<Long, Integer> owner(String value, List<Person> people, boolean line) {
        if (value.isEmpty() || (line && value.equals("egal")) || people.size() < 2) return Map.of();
        Person person = people.stream().filter(candidate -> String.valueOf(candidate.id()).equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Attribution invalide : cette personne n'existe plus."));
        return Shares.only(person.id(), people);
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
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(BudgetPages.edit(current, people(), request.form().values(), Notice.error(exception.getMessage()), back(request), request.csrf()));
        }
    }

    /** Retour au budget quand la depense y a ete ouverte ({@code ?retour=/budget...}). */
    private static String back(Request request) {
        String back = LocalPath.safe(request.query("retour"));
        return back != null && back.startsWith("/budget") ? back : null;
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
