package fr.patria.wizard;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.BudgetRepository;
import fr.patria.budget.Category;
import fr.patria.common.Frequency;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.expense.Expense;
import fr.patria.expense.ExpenseKind;
import fr.patria.expense.ExpenseRepository;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.household.Person;
import fr.patria.household.ShareRepository;
import fr.patria.household.Shares;
import fr.patria.household.Transfer;
import fr.patria.household.Visibility;
import fr.patria.loan.CsvScheduleParser;
import fr.patria.loan.LoanRepository;
import fr.patria.loan.ScheduleLine;
import fr.patria.property.Property;
import fr.patria.property.PropertyForm;
import fr.patria.property.PropertyRepository;
import fr.patria.rent.Occupancy;
import fr.patria.rent.RentPeriod;
import fr.patria.rent.RentRepository;
import fr.patria.ui.Html;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assistant d'import : un parcours en etapes pour remplir le foyer, du rassemblement des documents au recapitulatif.
 * Chaque etape se saisit sur place, peut etre passee, et la progression est gardee par personne connectee.
 *
 * <p>Deux facons de le parcourir : « Reprendre » saute les etapes faites ou passees ; « Tout revoir »
 * ({@code ?mode=revue}) les repasse toutes, avec ce qui est deja saisi. Un utilisateur n'a que les etapes de ses
 * propres lignes, avec les memes droits que dans l'administration (membres ouverts, lignes modifiables).
 */
public final class WizardController {
    /** Depots du foyer utilises par l'assistant. */
    public record Stores(HouseholdRepository household, BudgetRepository items, ShareRepository shares, PropertyRepository properties,
                         LoanRepository loans, ExpenseRepository charges, RentRepository rents, WizardRepository progress) {
    }

    static final String REVIEW = "revue";
    private static final int CUSTOM_ROWS = 3;
    private final Stores stores;
    private final Clock clock;

    public WizardController(Stores stores, Clock clock) {
        this.stores = stores;
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/assistant", request -> Response.page(WizardPages.home(context(request))));
        router.userPost("/assistant/recommencer", request -> {
            stores.progress().reset(owner(request));
            return Response.redirect("/assistant?ok=enregistre");
        });
        for (Step step : Step.values()) {
            Router.Handler page = request -> Response.page(render(request, step, request.confirmation(), Map.of()));
            Router.Handler skip = request -> finish(request, step, WizardRepository.Status.SKIPPED);
            Router.Handler done = request -> finish(request, step, WizardRepository.Status.DONE);
            if (step.adminOnly()) {
                router.get(step.path(), page);
                router.post(step.path() + "/passer", skip);
                router.post(step.path() + "/terminer", done);
            } else {
                router.userGet(step.path(), page);
                router.userPost(step.path() + "/passer", skip);
                router.userPost(step.path() + "/terminer", done);
            }
        }
        router.userPost("/assistant/documents/coche", this::checkDocument);
        router.post("/assistant/membres/ajouter", this::addMember);
        router.userPost("/assistant/revenus/ajouter", this::addIncome);
        router.userPost("/assistant/logement/ajouter", request -> addExpenses(request, Step.HOUSING, List.of(Checklist.RENT), false));
        router.userPost("/assistant/depenses/ajouter", request -> addExpenses(request, Step.FIXED, Checklist.FIXED, true));
        router.userPost("/assistant/impots/ajouter", request -> addExpenses(request, Step.TAXES, List.of(Checklist.TAXES), false));
        router.post("/assistant/biens/ajouter", this::addProperty);
        router.post("/assistant/prets/{id}", this::addLoan);
        router.post("/assistant/charges/{id}", this::addCharge);
        router.post("/assistant/occupation/{id}", this::addOccupancy);
        router.userPost("/assistant/virements/ajouter", this::addTransfer);

        // Revue annuelle : ce qui change d'une annee sur l'autre, ligne par ligne.
        router.userGet("/assistant/revue-annuelle", request -> Response.page(annualPage(request, request.confirmation())));
        router.userPost("/assistant/revue-annuelle/depense/{id}", this::reviewExpense);
        router.userPost("/assistant/revue-annuelle/revenu/{id}", this::reviewIncome);
        router.post("/assistant/revue-annuelle/taxe/{id}", this::reviewPropertyTax);
        router.post("/assistant/revue-annuelle/estimation/{id}", this::reviewEstimate);
        router.userPost("/assistant/encadre/ignorer", request -> {
            stores.progress().hideBanner(owner(request), LocalDate.now(clock));
            return Response.redirect("/");
        });
        router.userPost("/assistant/revue-annuelle/terminer", request -> {
            stores.progress().setReviewed(owner(request), LocalDate.now(clock));
            return Response.redirect("/assistant/revue-annuelle?ok=enregistre");
        });
    }

    // ---- Revue annuelle ----

    private Html annualPage(Request request, Notice notice) {
        Context context = context(request);
        return WizardPages.annual(context, stores.progress().reviewedOn(owner(request)), notice);
    }

    /**
     * Bandeau du Previsionnel quand la derniere revue a plus d'un an (ou, sans revue, le premier passage dans le guide
     * ou la plus ancienne donnee saisie). Vide sinon.
     */
    public Html reminder(Request request) {
        Context context = context(request);
        LocalDate oldest = java.util.stream.Stream.concat(context.snapshot().items().stream().map(BudgetItem::start),
                context.snapshot().incomes().stream().map(Income::start)).min(java.util.Comparator.naturalOrder()).orElse(null);
        if (!AnnualReview.isDue(stores.progress().reviewedOn(owner(request)), stores.progress().firstActivity(owner(request)), oldest, context.today())) return Html.EMPTY;
        return WizardPages.reminder(AnnualReview.thisMonth(context.today()));
    }

    /**
     * Encadre du Previsionnel « configuration terminee a x % » : etapes du guide faites ou passees, avec la prochaine
     * a faire. Vide quand tout est fait, quand il a ete ignore, ou pour un compte qui ne modifie rien (vues en lecture).
     */
    public Html progress(Request request) {
        if (!request.viewer().manages() && request.viewer().members().isEmpty()) return Html.EMPTY;
        String owner = owner(request);
        if (stores.progress().bannerHidden(owner)) return Html.EMPTY;
        List<Step> steps = Step.forViewer(request.viewer().manages()).stream().filter(step -> step != Step.SUMMARY).toList();
        Map<Step, WizardRepository.Status> progress = stores.progress().steps(owner);
        long finished = steps.stream().filter(progress::containsKey).count();
        if (finished == steps.size()) return Html.EMPTY;
        Step next = steps.stream().filter(step -> !progress.containsKey(step)).findFirst().orElse(Step.SUMMARY);
        return WizardPages.progress((int) (finished * 100 / steps.size()), (int) finished, steps.size(), next, request.csrf());
    }

    /** Nouveau montant a partir d'une date, ou arret de la depense (« Arrêtée »). Lignes modifiables seulement. */
    private Response reviewExpense(Request request) {
        BudgetItem item = stores.items().find(request.id()).orElseThrow(() -> new NotFoundException("Dépense introuvable", "Cette dépense n'existe pas."));
        Visibility visibility = Visibility.of(request.viewer(), stores.household().people());
        Map<Long, Integer> shares = stores.shares().of(Shares.Subject.BUDGET_ITEM, item.id());
        if (!visibility.canSee(shares)) throw new NotFoundException("Dépense introuvable", "Cette dépense n'existe pas.");
        visibility.requireEdit(visibility.canEdit(shares));
        try {
            FormData form = request.form();
            LocalDate from = form.date("from", "À partir du");
            if (item.end() != null && from.isAfter(item.end())) throw new ValidationException("Cette dépense est déjà terminée avant cette date.");
            if ("stop".equals(form.raw("action"))) {
                if (!from.isAfter(item.start())) throw new ValidationException("La dépense commence après cette date : supprimez-la plutôt.");
                stores.items().update(new BudgetItem(item.id(), item.label(), item.category(), item.amount(), item.frequency(), item.start(), from.minusDays(1)));
            } else {
                BigDecimal amount = form.positiveAmount("amount", "Nouveau montant");
                if (!from.isAfter(item.start())) {
                    stores.items().update(new BudgetItem(item.id(), item.label(), item.category(), amount, item.frequency(), item.start(), item.end()));
                } else {
                    // L'ancien montant s'arrete la veille, le nouveau prend le relais avec la meme repartition.
                    stores.items().update(new BudgetItem(item.id(), item.label(), item.category(), item.amount(), item.frequency(), item.start(), from.minusDays(1)));
                    long id = stores.items().insert(new BudgetItem(0, item.label(), item.category(), amount, item.frequency(), from, item.end()));
                    stores.shares().set(Shares.Subject.BUDGET_ITEM, id, shares);
                }
            }
            return annualBack("depenses");
        } catch (ValidationException exception) {
            return Response.invalid(annualPage(request, Notice.error(item.label() + " : " + exception.getMessage())));
        }
    }

    private Response reviewIncome(Request request) {
        Income income = stores.household().income(request.id()).orElseThrow(() -> new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas."));
        Visibility visibility = Visibility.of(request.viewer(), stores.household().people());
        if (!visibility.canSeeAny(income.personId())) throw new NotFoundException("Revenu introuvable", "Ce revenu n'existe pas.");
        visibility.requireEdit(visibility.sees(income.personId()));
        try {
            FormData form = request.form();
            LocalDate from = form.date("from", "À partir du");
            if (income.end() != null && from.isAfter(income.end())) throw new ValidationException("Ce revenu est déjà terminé avant cette date.");
            if ("stop".equals(form.raw("action"))) {
                if (!from.isAfter(income.start())) throw new ValidationException("Le revenu commence après cette date : supprimez-le plutôt.");
                stores.household().updateIncome(new Income(income.id(), income.personId(), income.label(), income.kind(), income.amount(), income.frequency(), income.start(),
                        from.minusDays(1)));
            } else {
                BigDecimal amount = form.positiveAmount("amount", "Nouveau montant");
                if (!from.isAfter(income.start())) {
                    stores.household().updateIncome(new Income(income.id(), income.personId(), income.label(), income.kind(), amount, income.frequency(), income.start(), income.end()));
                } else {
                    stores.household().updateIncome(new Income(income.id(), income.personId(), income.label(), income.kind(), income.amount(), income.frequency(), income.start(),
                            from.minusDays(1)));
                    stores.household().addIncome(new Income(0, income.personId(), income.label(), income.kind(), amount, income.frequency(), from, income.end()));
                }
            }
            return annualBack("revenus");
        } catch (ValidationException exception) {
            return Response.invalid(annualPage(request, Notice.error(income.label() + " : " + exception.getMessage())));
        }
    }

    private Response reviewPropertyTax(Request request) {
        Property property = property(request);
        try {
            stores.charges().save(new Expense(0, property.id(), ExpenseKind.PROPERTY_TAX, LocalDate.now(clock).getYear(), request.form().positiveAmount("amount", "Taxe foncière")));
            return annualBack("biens");
        } catch (ValidationException exception) {
            return Response.invalid(annualPage(request, Notice.error(property.name() + " : " + exception.getMessage())));
        }
    }

    /** Nouvelle estimation datee d'aujourd'hui ; la valeur choisie au curseur repart du scenario prudent. */
    private Response reviewEstimate(Request request) {
        Property property = property(request);
        try {
            FormData form = request.form();
            BigDecimal low = form.positiveAmount("estimate", "Estimation prudente");
            BigDecimal high = form.optionalAmount("estimate_high", "Estimation optimiste");
            if (high != null && high.signum() > 0 && high.compareTo(low) < 0) throw new ValidationException("L'estimation optimiste doit être supérieure ou égale à la prudente.");
            stores.properties().update(new Property(property.id(), property.name(), property.kind(), property.commune(), property.address(), property.area(), property.purchase(),
                    property.purchaseDate(), low, high == null || high.signum() == 0 ? null : high, LocalDate.now(clock), property.saleFeesRate(), null));
            return annualBack("biens");
        } catch (ValidationException exception) {
            return Response.invalid(annualPage(request, Notice.error(property.name() + " : " + exception.getMessage())));
        }
    }

    private static Response annualBack(String anchor) {
        return Response.redirect("/assistant/revue-annuelle?ok=enregistre#" + anchor);
    }

    // ---- Contexte ----

    /** Tout ce qu'une page de l'assistant affiche, calcule une fois par requete. */
    record Context(boolean admin, boolean review, List<Step> steps, Map<Step, WizardRepository.Status> progress, Map<String, WizardRepository.DocumentStatus> documents,
                   Snapshot snapshot, Visibility visibility, Map<Shares.Key, Map<Long, Integer>> shares, LocalDate today, String csrf) {
        String mode() {
            return review ? "?mode=" + REVIEW : "";
        }

        /** Etape suivante : la suivante dans l'ordre en revue, sinon la premiere non faite ni passee apres celle-ci. */
        Step next(Step current) {
            int index = steps.indexOf(current);
            for (int i = index + 1; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (review || step == Step.SUMMARY || !progress.containsKey(step)) return step;
            }
            return Step.SUMMARY;
        }

        Step previous(Step current) {
            int index = steps.indexOf(current);
            return index <= 0 ? null : steps.get(index - 1);
        }

        /** Premiere etape a faire (« Reprendre »). */
        Step resume() {
            return steps.stream().filter(step -> step != Step.SUMMARY && !progress.containsKey(step)).findFirst().orElse(Step.SUMMARY);
        }
    }

    private Context context(Request request) {
        boolean admin = request.viewer().manages();
        List<Person> people = stores.household().people();
        Visibility visibility = Visibility.of(request.viewer(), people);
        Map<Shares.Key, Map<Long, Integer>> shares = stores.shares().all();
        List<BudgetItem> items = stores.items().all().stream()
                .filter(item -> admin || visibility.canSee(shares.getOrDefault(new Shares.Key(Shares.Subject.BUDGET_ITEM, item.id()), Map.of()))).toList();
        List<Income> incomes = stores.household().incomes().stream().filter(income -> admin || visibility.canSeeAny(income.personId())).toList();
        List<Transfer> transfers = stores.household().transfers().stream().filter(transfer -> admin || visibility.canSeeAny(transfer.fromId(), transfer.toId())).toList();
        Snapshot snapshot = admin
                ? new Snapshot(people, incomes, items, stores.properties().all(), stores.loans().all(), stores.charges().all(), stores.rents().all(), transfers)
                : new Snapshot(people, incomes, items, List.of(), List.of(), List.of(), List.of(), transfers);
        return new Context(admin, REVIEW.equals(request.query("mode")) || REVIEW.equals(request.form().raw("mode")), Step.forViewer(admin),
                stores.progress().steps(owner(request)), stores.progress().documents(owner(request)), snapshot, visibility, shares, LocalDate.now(clock), request.csrf());
    }

    /** Progression propre a la personne connectee : l'administrateur, ou chaque utilisateur. */
    private static String owner(Request request) {
        return request.viewer().isAdministrator() ? "admin" : "user:" + request.viewer().userId();
    }

    private Html render(Request request, Step step, Notice notice, Map<String, String> values) {
        Context context = context(request);
        if (!context.steps().contains(step)) throw new NotFoundException("Étape introuvable", "Cette étape n'est pas proposée pour votre compte.");
        return WizardPages.step(context, step, notice, values);
    }

    private Response finish(Request request, Step step, WizardRepository.Status status) {
        Context context = context(request);
        if (step != Step.SUMMARY) stores.progress().setStep(owner(request), step, status, context.today());
        Step next = context.next(step);
        return Response.redirect(next.path() + context.mode());
    }

    /** Retour sur l'etape apres un ajout, en gardant le mode. */
    private static Response back(Request request, Step step, String anchor) {
        String mode = REVIEW.equals(request.form().raw("mode")) ? "&mode=" + REVIEW : "";
        return Response.redirect(step.path() + "?ok=enregistre" + mode + (anchor == null ? "" : "#" + anchor));
    }

    private Response invalid(Request request, Step step, ValidationException exception) {
        return Response.invalid(render(request, step, Notice.error(exception.getMessage()), request.form().values()));
    }

    // ---- Documents ----

    private Response checkDocument(Request request) {
        String id = request.form().raw("document");
        if (Documents.ALL.stream().noneMatch(document -> document.id().equals(id))) throw new ValidationException("Document inconnu.");
        String raw = request.form().raw("status");
        WizardRepository.DocumentStatus status = raw.isEmpty() ? null : switch (raw) {
            case "HAVE" -> WizardRepository.DocumentStatus.HAVE;
            case "NOT_CONCERNED" -> WizardRepository.DocumentStatus.NOT_CONCERNED;
            default -> throw new ValidationException("Choix invalide.");
        };
        stores.progress().setDocument(owner(request), id, status);
        return back(request, Step.DOCUMENTS, "doc-" + id);
    }

    // ---- Membres, revenus, virements ----

    private Response addMember(Request request) {
        try {
            String kind = request.form().raw("kind");
            stores.household().addMember(request.form().requiredText("name", "Nom", 60), kind.isBlank() ? Person.Kind.PERSON : Person.Kind.parse(kind));
            return back(request, Step.MEMBERS, null);
        } catch (ValidationException exception) {
            return invalid(request, Step.MEMBERS, exception);
        }
    }

    private Response addIncome(Request request) {
        try {
            FormData form = request.form();
            // Comme la page : les revenus se rattachent aux personnes (pas au compte joint) dont la vue est ouverte.
            List<Person> open = Visibility.of(request.viewer(), stores.household().people()).people().stream().filter(candidate -> !candidate.isAccount()).toList();
            long person = member(form.raw("person"), open, "Personne");
            Frequency frequency = Frequency.parse(form.raw("frequency"));
            LocalDate start = form.date("start", "Premier versement");
            stores.household().addIncome(new Income(0, person, form.requiredText("label", "Libellé", 100), IncomeKind.parse(form.raw("kind")),
                    form.positiveAmount("amount", "Montant"), frequency, start, null));
            return back(request, Step.INCOMES, "revenus-" + person);
        } catch (ValidationException exception) {
            return invalid(request, Step.INCOMES, exception);
        }
    }

    private Response addTransfer(Request request) {
        try {
            FormData form = request.form();
            List<Person> open = Visibility.of(request.viewer(), stores.household().people()).people();
            long from = member(form.raw("from"), open, "Émetteur");
            long to = member(form.raw("to"), open, "Destinataire");
            if (from == to) throw new ValidationException("L'émetteur et le destinataire doivent être différents.");
            Frequency frequency = Frequency.parse(form.raw("frequency"));
            stores.household().addTransfer(new Transfer(0, from, to, form.optionalText("label", "Libellé", 100), form.positiveAmount("amount", "Montant"),
                    frequency, form.date("start", "Premier virement"), null));
            return back(request, Step.TRANSFERS, null);
        } catch (ValidationException exception) {
            return invalid(request, Step.TRANSFERS, exception);
        }
    }

    private static long member(String raw, List<Person> people, String label) {
        try {
            long id = Long.parseLong(raw);
            if (people.stream().anyMatch(person -> person.id() == id)) return id;
        } catch (NumberFormatException ignored) {
            // message commun ci-dessous
        }
        throw new ValidationException(label + " : choisissez un membre de la liste.");
    }

    // ---- Depenses par listes a cocher ----

    /**
     * Ajoute les lignes cochees (ou dont le montant est rempli) des listes de l'etape : tout ou rien, chaque erreur
     * est signalee avec le nom de la ligne. Les lignes deja saisies ne sont pas proposees.
     */
    private Response addExpenses(Request request, Step step, List<Checklist.Group> groups, boolean custom) {
        FormData form = request.form();
        Visibility visibility = Visibility.of(request.viewer(), stores.household().people());
        LocalDate defaultStart = LocalDate.now(clock).withDayOfMonth(1);
        List<BudgetRepository.NewExpense> fresh = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        // Lignes deja saisies (visibles par la personne connectee) : jamais recreees, meme si la page renvoyee est ancienne.
        Snapshot snapshot = context(request).snapshot();
        int skipped = 0;
        for (Checklist.Group group : groups) {
            for (Checklist.Item item : group.items()) {
                String amount = form.raw("amount_" + item.id()).trim();
                boolean checked = "on".equals(form.raw("on_" + item.id())) || !amount.isEmpty();
                if (!checked) continue;
                if (!item.repeatable() && snapshot.item(item.label()).isPresent()) {
                    skipped++;
                    continue;
                }
                try {
                    fresh.add(expense(form, item.id(), item.label(), item.category(), visibility, defaultStart));
                } catch (ValidationException exception) {
                    errors.add(item.label() + " : " + exception.getMessage());
                }
            }
        }
        if (custom) {
            for (int row = 1; row <= CUSTOM_ROWS; row++) {
                String id = "custom" + row;
                String label = form.raw("label_" + id).trim();
                if (label.isEmpty() && form.raw("amount_" + id).isBlank()) continue;
                try {
                    if (label.isEmpty()) throw new ValidationException("indiquez un libellé.");
                    if (label.length() > 100) throw new ValidationException("libellé trop long (100 caractères au plus).");
                    fresh.add(expense(form, id, label, Category.parse(form.raw("category_" + id)), visibility, defaultStart));
                } catch (ValidationException exception) {
                    errors.add((label.isEmpty() ? "Autre dépense " + row : label) + " : " + exception.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            return Response.invalid(render(request, step, Notice.error("Rien n'est enregistré. " + String.join(" · ", errors)), form.values()));
        }
        if (fresh.isEmpty() && skipped > 0) return back(request, step, null);
        if (fresh.isEmpty()) {
            return Response.invalid(render(request, step, Notice.error("Cochez au moins une dépense et indiquez son montant."), form.values()));
        }
        stores.items().insertAll(fresh);
        return back(request, step, null);
    }

    private static BudgetRepository.NewExpense expense(FormData form, String id, String label, Category category, Visibility visibility, LocalDate defaultStart) {
        BigDecimal amount = form.positiveAmount("amount_" + id, "Montant");
        Frequency frequency = Frequency.parse(form.raw("frequency_" + id));
        LocalDate start = form.raw("start_" + id).isBlank() ? defaultStart : form.date("start_" + id, "Premier paiement");
        String who = form.raw("who_" + id).trim();
        Map<Long, Integer> shares;
        if (who.isEmpty()) {
            shares = Map.of();
        } else {
            long person = member(who, visibility.people(), "Pour qui");
            shares = Shares.only(person, visibility.everyone());
        }
        return new BudgetRepository.NewExpense(new BudgetItem(0, label, category, amount, frequency, start, null), visibility.normalize(shares));
    }

    // ---- Immobilier (administrateur) ----

    private Response addProperty(Request request) {
        try {
            stores.properties().insert(PropertyForm.read(request.form(), null, LocalDate.now(clock)));
            return back(request, Step.PROPERTIES, null);
        } catch (ValidationException exception) {
            return invalid(request, Step.PROPERTIES, exception);
        }
    }

    private Property property(Request request) {
        return stores.properties().find(request.id()).orElseThrow(() -> new NotFoundException("Bien introuvable", "Ce bien n'existe pas."));
    }

    private Response addLoan(Request request) {
        Property property = property(request);
        try {
            String name = request.form().requiredText("name", "Nom du prêt", 100);
            List<ScheduleLine> lines = CsvScheduleParser.parse(request.form().requiredFile("csv", "Fichier CSV"));
            stores.loans().create(property.id(), name, lines);
            return back(request, Step.LOANS, "bien-" + property.id());
        } catch (ValidationException exception) {
            return invalid(request, Step.LOANS, exception);
        }
    }

    private Response addCharge(Request request) {
        Property property = property(request);
        try {
            FormData form = request.form();
            int year;
            try {
                year = Integer.parseInt(form.raw("year").trim());
            } catch (NumberFormatException exception) {
                throw new ValidationException("Année invalide.");
            }
            if (year < 1950 || year > LocalDate.now(clock).getYear() + 1) throw new ValidationException("Année invalide.");
            stores.charges().save(new Expense(0, property.id(), ExpenseKind.parse(form.raw("kind")), year, form.positiveAmount("amount", "Montant")));
            return back(request, Step.CHARGES, "bien-" + property.id());
        } catch (ValidationException exception) {
            return invalid(request, Step.CHARGES, exception);
        }
    }

    private Response addOccupancy(Request request) {
        Property property = property(request);
        try {
            FormData form = request.form();
            RentPeriod period = new RentPeriod(0, property.id(), Occupancy.parse(form.raw("kind")), "", form.date("start", "Début"), null,
                    form.positiveAmount("monthly", "Loyer mensuel"));
            fr.patria.rent.RentMath.requireNoOverlap(stores.rents().forProperty(property.id()), period);
            stores.rents().insert(period);
            return back(request, Step.CHARGES, "bien-" + property.id());
        } catch (ValidationException exception) {
            return invalid(request, Step.CHARGES, exception);
        }
    }
}
