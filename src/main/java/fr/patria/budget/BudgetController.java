package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import fr.patria.household.Visibility;
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

    /** Encarts du Previsionnel fournis par d'autres modules (revue annuelle, bilan de sante), assembles par l'application. */
    private java.util.function.Function<Request, fr.patria.ui.Html> homeExtras = request -> fr.patria.ui.Html.EMPTY;

    public BudgetController withHomeExtras(java.util.function.Function<Request, fr.patria.ui.Html> extras) {
        this.homeExtras = extras;
        return this;
    }

    public BudgetController(Forecast forecast, Forecast.Sources sources, Clock clock) {
        this.forecast = forecast;
        this.sources = sources;
        this.items = sources.items();
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/", request -> Response.page(BudgetPages.home(view(request), homeExtras.apply(request), request.csrf())));
        router.userGet("/budget", request -> {
            Forecast.View view = view(request);
            return Response.page(BudgetPages.budget(view, forecast.averageIncomes(view), forecast.averageExpenses(view), request.csrf()));
        });
        router.userGet("/previsionnel.pdf", request -> pdf("previsionnel", BudgetReports.forecast(view(request), LocalDate.now(clock))));
        router.userGet("/budget-moyen.pdf", request -> {
            Forecast.View view = view(request);
            return pdf("budget-moyen", BudgetReports.average(view, forecast.averageIncomes(view), forecast.averageExpenses(view), LocalDate.now(clock)));
        });
        router.userGet("/administration", request -> Response.redirect(request.viewer().manages() ? "/administration/membres" : "/administration/revenus"));
        router.userGet("/administration/depenses", request -> Response.page(adminPage(request, defaults(), request.confirmation())));
        router.userPost("/administration/depenses", this::create);
        router.userGet("/administration/depenses/modele.csv", request -> new Response.Download("modele-depenses.csv", "text/csv; charset=utf-8",
                ExpenseCsv.template().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        router.userPost("/administration/depenses/import", this::preview);
        router.userPost("/administration/depenses/import/valider", this::confirmImport);
        router.userGet("/depense/{id}", request -> {
            BudgetItem item = visible(request);
            Visibility visibility = visibility(request);
            Map<Long, Integer> own = sources.shares().of(Shares.Subject.BUDGET_ITEM, item.id());
            boolean readOnly = !visibility.canEdit(own);
            List<Person> people = readOnly ? visibility.everyone() : visibility.people();
            Map<String, String> values = new HashMap<>(BudgetPages.values(item));
            values.putAll(Shares.values(own, people));
            return Response.page(BudgetPages.edit(item, people, values, null, back(request), readOnly, request.csrf()));
        });
        router.userPost("/depense/{id}", this::update);
        router.userPost("/depense/{id}/supprimer", request -> {
            long id = editable(request).id();
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

    private Response pdf(String name, byte[] content) {
        return new Response.Download(name + "-" + LocalDate.now(clock) + ".pdf", "application/pdf", content);
    }

    private List<Person> people() {
        return sources.household().people();
    }

    private Visibility visibility(Request request) {
        return Visibility.of(request.viewer(), people());
    }

    /**
     * Previsionnel affiche : la personne demandee si sa vue est ouverte, sinon le foyer (si ouvert) ou la premiere
     * vue ouverte. Le selecteur ne propose que les vues ouvertes.
     */
    private Forecast.View view(Request request) {
        return BudgetScope.view(forecast, visibility(request), person(request));
    }

    /** Depenses visibles par la personne connectee, et celles qu'elle ne peut que consulter. */
    private fr.patria.ui.Html adminPage(Request request, Map<String, String> values, Notice notice) {
        Visibility visibility = visibility(request);
        Map<Shares.Key, Map<Long, Integer>> shares = sources.shares().all();
        List<BudgetItem> visible = new java.util.ArrayList<>();
        java.util.Set<Long> readOnly = new java.util.HashSet<>();
        for (BudgetItem item : items.all()) {
            Map<Long, Integer> own = shares.getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of());
            if (!visibility.canSee(own)) continue;
            visible.add(item);
            if (!visibility.canEdit(own)) readOnly.add(item.id());
        }
        return BudgetPages.admin(visible, shares, people(), visibility.people(), readOnly, sources.properties().all(), values, notice, request.csrf());
    }

    /**
     * Depenses visibles par la personne connectee : base de la detection des doublons a l'import, pour qu'un
     * utilisateur ne puisse pas deviner, en important un fichier, une depense qu'il ne voit pas.
     */
    private List<BudgetItem> visibleItems(Request request) {
        Visibility visibility = visibility(request);
        if (visibility.manages()) return items.all();
        Map<Shares.Key, Map<Long, Integer>> shares = sources.shares().all();
        return items.all().stream().filter(item -> visibility.canSee(shares.getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of()))).toList();
    }

    /** Depense visible par la personne connectee (404 sinon). */
    private BudgetItem visible(Request request) {
        BudgetItem item = require(request.id());
        if (!visibility(request).canSee(sources.shares().of(Shares.Subject.BUDGET_ITEM, item.id()))) {
            throw new NotFoundException("Dépense introuvable", "Cette dépense n'existe pas.");
        }
        return item;
    }

    /** Depense que la personne connectee peut modifier (403 si elle concerne un membre non ouvert, 404 si invisible). */
    private BudgetItem editable(Request request) {
        BudgetItem item = visible(request);
        Visibility visibility = visibility(request);
        visibility.requireEdit(visibility.canEdit(sources.shares().of(Shares.Subject.BUDGET_ITEM, item.id())));
        return item;
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
            List<ExpenseCsv.Row> rows = ExpenseCsv.parse(content, visibleItems(request));
            boolean ok = rows.stream().allMatch(ExpenseCsv.Row::valid);
            return new Response.Page(ok ? 200 : 400, BudgetPages.importPreview(rows, content, visibility(request).people(), Map.of(), request.csrf()));
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(request, defaults(), Notice.error("Import impossible. " + exception.getMessage())));
        }
    }

    /** Le contenu est relu et reverifie : l'apercu ne sert qu'a l'affichage. */
    private Response confirmImport(Request request) {
        String content;
        try {
            content = new String(java.util.Base64.getUrlDecoder().decode(request.form().raw("contenu")), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return Response.invalid(adminPage(request, defaults(), Notice.error("Import impossible : contenu illisible, recommencez depuis le fichier.")));
        }
        try {
            List<ExpenseCsv.Row> rows = ExpenseCsv.parse(content, visibleItems(request));
            Visibility visibility = visibility(request);
            List<Person> people = visibility.people();
            if (!rows.stream().allMatch(ExpenseCsv.Row::valid)) return Response.invalid(BudgetPages.importPreview(rows, content, people, Map.of(), request.csrf()));
            Map<String, String> form = request.form().values();
            Map<Long, Integer> all = owner(form.getOrDefault("attribution", ""), people, false);
            List<BudgetRepository.NewExpense> fresh = rows.stream().filter(ExpenseCsv.Row::fresh).map(row -> {
                String own = form.getOrDefault("attribution_" + row.line(), "");
                return new BudgetRepository.NewExpense(row.item(), visibility.normalize(own.isEmpty() ? all : owner(own, people, true)));
            }).toList();
            items.insertAll(fresh);
            return Response.redirect("/administration/depenses?ok=importe");
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(request, defaults(), Notice.error("Import impossible. " + exception.getMessage())));
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
            Visibility visibility = visibility(request);
            Map<Long, Integer> shares = visibility.normalize(Shares.parse(request.form().values(), visibility.people()));
            long id = items.insert(item);
            sources.shares().set(Shares.Subject.BUDGET_ITEM, id, shares);
            return Response.redirect("/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(adminPage(request, request.form().values(), Notice.error(exception.getMessage())));
        }
    }

    private Response update(Request request) {
        BudgetItem current = editable(request);
        Visibility visibility = visibility(request);
        try {
            BudgetItem item = read(request.form(), current.id());
            Map<Long, Integer> shares = visibility.normalize(Shares.parse(request.form().values(), visibility.people()));
            items.update(item);
            sources.shares().set(Shares.Subject.BUDGET_ITEM, current.id(), shares);
            String back = back(request);
            return Response.redirect(back != null ? back : "/administration/depenses?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(BudgetPages.edit(current, visibility.people(), request.form().values(), Notice.error(exception.getMessage()), back(request), false, request.csrf()));
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
