// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.savings;

import fr.patria.budget.BudgetScope;
import fr.patria.budget.Forecast;
import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.household.HouseholdRepository;
import fr.patria.household.Person;
import fr.patria.household.Visibility;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Page Epargne : livrets, assurance-vie, PEA et comptes-titres de chaque membre, cours de bourse et inflation, plan
 * d'epargne de securite. Chaque compte appartient a un membre : il est visible et modifiable par ceux qui ont acces a
 * ce membre.
 */
public final class SavingsController {
    private final SavingsRepository savings;
    private final HouseholdRepository household;
    private final Forecast forecast;
    private final MarketFeeds feeds;
    private final Clock clock;
    private final fr.patria.budget.BudgetRepository budget;

    public SavingsController(SavingsRepository savings, HouseholdRepository household, Forecast forecast, fr.patria.budget.BudgetRepository budget,
                             MarketFeeds feeds, Clock clock) {
        this.savings = savings;
        this.budget = budget;
        this.household = household;
        this.forecast = forecast;
        this.feeds = feeds;
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/epargne", request -> Response.page(page(request, request.confirmation(), false)));
        router.userPost("/epargne/compte", this::addAccount);
        router.userPost("/epargne/compte/{id}", request -> {
            SavingsRepository.Account account = editable(request);
            try {
                savings.updateBalance(account.id(), request.form().amount("balance", "Solde"), LocalDate.now(clock));
                return done(request, "enregistre", "compte-" + account.id(), account.kind().stocks());
            } catch (ValidationException exception) {
                return Response.invalid(page(request, Notice.error(exception.getMessage()), account.kind().stocks()));
            }
        });
        router.userPost("/epargne/compte/{id}/versement", this::setContribution);
        router.userPost("/epargne/compte/{id}/supprimer", request -> {
            SavingsRepository.Account account = editable(request);
            if (account.contribution() != null && account.contribution().item() != null) budget.deleteWithShares(account.contribution().item());
            savings.deleteAccount(account.id());
            return done(request, "supprime", null, account.kind().stocks());
        });
        router.userPost("/epargne/fiscal", request -> {
            String back = back(request);
            try {
                String raw = request.form().raw("rfr").replace(" ", "").replace("\u00a0", "").replace("\u202f", "");
                if (raw.isEmpty()) setTaxInfo(null, 0, BigDecimal.ONE);
                else setTaxInfo(new BigDecimal(raw.replace(',', '.')), Integer.parseInt(request.form().raw("annee").strip()),
                        new BigDecimal(request.form().raw("parts").strip().replace(',', '.')));
            } catch (NumberFormatException exception) {
                throw new ValidationException("Revenu, année et parts : des nombres (ex. 32450, 2026, 2,5).");
            }
            return Response.redirect((back == null ? "/epargne" : back) + "?ok=enregistre#fiscal");
        });
        router.userPost("/epargne/plan", request -> {
            String lep = request.form().raw("lep");
            savings.setPreference("lep", lep.equals("oui") || lep.equals("non") ? lep : "");
            int months = months(request.form().raw("mois"));
            savings.setPreference("months", String.valueOf(months));
            return Response.redirect("/epargne?ok=enregistre#plan");
        });
    }

    /** Apres une action : retour a l'etape du guide d'ou elle vient ({@code retour}), sinon a la page Epargne. */
    private static Response done(Request request, String ok, String anchor, boolean stocks) {
        String back = back(request);
        return Response.redirect(fr.patria.web.LocalPath.withOk(back != null ? back : stocks ? "/bourse" : "/epargne", ok) + (anchor == null ? "" : "#" + anchor));
    }

    /** Formulaire d'ajout de compte en erreur : retour a la page Bourse pour un PEA ou un compte-titres. */
    private static boolean stocksForm(FormData form) {
        try {
            return SavingsKind.parse(form.raw("kind")).stocks();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    static String back(Request request) {
        return fr.patria.web.LocalPath.assistant(request.form().raw("retour"));
    }

    /** Versement programme : le solde avance a chaque echeance, une ligne de budget (Virements) le reprend. */
    private Response setContribution(Request request) {
        SavingsRepository.Account account = editable(request);
        FormData form = request.form();
        try {
            LocalDate today = LocalDate.now(clock);
            // Le solde du jour (versements deja comptes) devient le nouveau point de depart.
            savings.updateBalance(account.id(), Savings.balance(account, today), today);
            if (account.contribution() != null && account.contribution().item() != null) budget.deleteWithShares(account.contribution().item());
            if (form.raw("supprimer").isBlank()) {
                BigDecimal amount = form.amount("montant", "Montant du versement");
                if (amount.signum() <= 0) throw new ValidationException("Montant du versement : plus de 0 €.");
                fr.patria.common.Frequency frequency = form.raw("frequence").isBlank() ? fr.patria.common.Frequency.MONTHLY : fr.patria.common.Frequency.parse(form.raw("frequence"));
                if (frequency == fr.patria.common.Frequency.ONCE) throw new ValidationException("Un versement programmé revient régulièrement : choisissez une fréquence.");
                LocalDate start = form.raw("debut").isBlank() ? today : form.date("debut", "Premier versement");
                long item = budget.insert(new fr.patria.budget.BudgetItem(0, "Versement " + account.name(), fr.patria.budget.Category.TRANSFER, amount, frequency, start, null),
                        fr.patria.household.Shares.only(account.personId(), household.people()));
                savings.setContribution(account.id(), new SavingsRepository.Contribution(amount, frequency, start, item));
            } else {
                savings.setContribution(account.id(), null);
            }
            return done(request, "enregistre", "compte-" + account.id(), account.kind().stocks());
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error(exception.getMessage()), account.kind().stocks()));
        }
    }

    /** Ce que voit la personne connectee : ses comptes, la vue, le plan (partage avec le bilan). */
    public record Snapshot(Savings.Overview overview, Savings.Plan plan, java.util.Optional<MarketFeeds.Inflation> inflation,
                           java.util.Optional<Savings.PurchasingPower> power, String lep, List<Person> holders, boolean lepFromTax) {
        public Snapshot(Savings.Overview overview, Savings.Plan plan, java.util.Optional<MarketFeeds.Inflation> inflation,
                        java.util.Optional<Savings.PurchasingPower> power, String lep, List<Person> holders) {
            this(overview, plan, inflation, power, lep, holders, false);
        }
    }

    /**
     * Revenu fiscal de reference du foyer fiscal (avis d'impot), avec l'annee de l'avis et le nombre de parts. Sert a
     * l'eligibilite au LEP ; saisi dans le Guide (etape Impots), sur la page Epargne ou par la reponse de l'IA.
     */
    public record TaxInfo(BigDecimal income, int year, BigDecimal parts) {
        /** Plafond du LEP pour ce nombre de parts : 1 part, plus une somme par demi-part supplementaire. */
        public BigDecimal lepCeiling() {
            BigDecimal halves = parts.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(2)).max(BigDecimal.ZERO);
            return RegulatedRates.LEP_INCOME_ONE_PART.add(RegulatedRates.LEP_INCOME_HALF_PART.multiply(halves));
        }

        public boolean lepEligible() {
            return income.compareTo(lepCeiling()) <= 0;
        }
    }

    public java.util.Optional<TaxInfo> taxInfo() {
        try {
            String income = savings.preference("rfr");
            if (income.isEmpty()) return java.util.Optional.empty();
            return java.util.Optional.of(new TaxInfo(new BigDecimal(income), Integer.parseInt(savings.preference("rfr_annee")), new BigDecimal(savings.preference("parts"))));
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    /** {@code income} nul : efface. Verifie des valeurs plausibles (avis d'impot francais). */
    public void setTaxInfo(BigDecimal income, int year, BigDecimal parts) {
        if (income == null) {
            savings.setPreference("rfr", "");
            return;
        }
        if (income.signum() < 0 || income.compareTo(BigDecimal.valueOf(10_000_000)) > 0) throw new ValidationException("Revenu fiscal de référence invraisemblable.");
        if (year < 2015 || year > LocalDate.now(clock).getYear()) throw new ValidationException("Année de l'avis invraisemblable.");
        if (parts.compareTo(BigDecimal.ONE) < 0 || parts.compareTo(BigDecimal.valueOf(20)) > 0 || parts.remainder(new BigDecimal("0.25")).signum() != 0) {
            throw new ValidationException("Nombre de parts invalide (1, 1,5, 2, 2,5…).");
        }
        savings.setPreference("rfr", income.setScale(0, RoundingMode.HALF_UP).toPlainString());
        savings.setPreference("rfr_annee", String.valueOf(year));
        savings.setPreference("parts", parts.stripTrailingZeros().toPlainString());
    }

    /** Taux moyen de l'epargne visible (hors bourse), pondere par les soldes ; {@code null} sans epargne. */
    public BigDecimal averageRate(Request request) {
        Savings.Overview overview = snapshot(request, java.util.Set.of()).overview();
        List<Savings.Line> lines = overview.lines().stream().filter(line -> !line.account().kind().stocks()).toList();
        BigDecimal total = lines.stream().map(Savings.Line::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) return null;
        BigDecimal yearly = lines.stream().map(Savings.Line::yearlyInterest).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return yearly.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    /** Eligibilite au LEP : deduite du revenu fiscal de reference s'il est connu, sinon la reponse donnee (« oui », « non », vide). */
    String lep() {
        return taxInfo().map(info -> info.lepEligible() ? "oui" : "non").orElse(savings.preference("lep"));
    }

    /** Formulaire du revenu fiscal de reference ; {@code back} : page de retour (Guide ou Epargne). */
    public fr.patria.ui.Html taxForm(String back, String csrf) {
        java.util.Optional<TaxInfo> info = taxInfo();
        fr.patria.ui.Html status = info.map(value -> fr.patria.ui.Html.of("<p class=\"%s\">%s</p>", value.lepEligible() ? "notice success" : "muted",
                        value.lepEligible() ? "Revenu sous le plafond du LEP (" + fr.patria.ui.Format.euroWhole(value.lepCeiling()) + " pour " + fr.patria.ui.Format.number(value.parts(), 2)
                                + " part" + (value.parts().compareTo(BigDecimal.ONE) > 0 ? "s" : "") + ") : vous pouvez ouvrir un LEP, le livret réglementé le mieux rémunéré."
                                : "Revenu au-dessus du plafond du LEP (" + fr.patria.ui.Format.euroWhole(value.lepCeiling()) + ") : pas d'ouverture possible cette année."))
                .orElse(fr.patria.ui.Html.EMPTY);
        return fr.patria.ui.Html.of("""
                <form method="post" action="/epargne/fiscal" class="stack">%s<input type="hidden" name="retour" value="%s">\
                <p class="muted">Sur votre dernier avis d'impôt (impots.gouv.fr → Documents), en haut de la première page : « Revenu fiscal de référence » et « Nombre de parts ».
                Patria s'en sert pour savoir si vous pouvez ouvrir un LEP.</p>%s<div class="form-grid cols-3">\
                <label>Revenu fiscal de référence (€)<input name="rfr" inputmode="numeric" value="%s" placeholder="32 450"></label>\
                <label>Année de l'avis<input name="annee" type="number" min="2015" max="%s" value="%s"></label>\
                <label>Nombre de parts<input name="parts" inputmode="decimal" value="%s" placeholder="2"></label></div>\
                <div class="form-actions"><button class="secondary" type="submit">Enregistrer</button></div></form>""",
                fr.patria.ui.Components.csrfField(csrf), back.isEmpty() ? fr.patria.ui.Html.EMPTY : fr.patria.ui.Html.of("<input type=\"hidden\" name=\"retour\" value=\"%s\">", back), status, info.map(value -> value.income().toPlainString()).orElse(""), LocalDate.now(clock).getYear(),
                info.map(value -> String.valueOf(value.year())).orElse(String.valueOf(LocalDate.now(clock).getYear())),
                info.map(value -> value.parts().toPlainString().replace('.', ',')).orElse(""));
    }

    /** Epargne visible, plan et inflation ; {@code scope} non vide : seulement ces membres (perimetre du bilan). */
    public Snapshot snapshot(Request request, java.util.Set<Long> scope) {
        LocalDate today = LocalDate.now(clock);
        List<Person> people = household.people();
        Visibility visibility = Visibility.of(request.viewer(), people);
        java.util.function.LongPredicate in = person -> visibility.sees(person) && (scope.isEmpty() || scope.contains(person));
        List<SavingsRepository.Account> accounts = savings.accounts().stream().filter(account -> in.test(account.personId())).toList();
        Savings.Overview overview = Savings.overview(accounts, savings.positions(), Savings.owners(people), feeds::quote, today);
        Forecast.View view = scope.isEmpty() ? BudgetScope.view(forecast, visibility, null) : forecast.next12MonthsOf(scope);
        // Les versements vers l'epargne (Virements) ne sont pas des depenses a couvrir par la precaution.
        BigDecimal spending = forecast.averageExpenses(view).stream().filter(line -> line.category() != fr.patria.budget.Category.TRANSFER)
                .map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);
        BigDecimal margin = view.months().isEmpty() ? BigDecimal.ZERO : view.months().stream().map(Forecast.Month::balance).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(view.months().size()), 0, RoundingMode.HALF_UP);
        List<Person> holders = people.stream().filter(person -> !person.isAccount() && in.test(person.id())).toList();
        String lep = lep();
        Savings.Plan plan = Savings.plan(spending, margin, overview, holders.stream().map(Person::id).toList(), months(savings.preference("months")), lep, today);
        java.util.Optional<MarketFeeds.Inflation> inflation = feeds.inflation();
        java.util.Optional<Savings.PurchasingPower> power = Savings.purchasingPower(household.incomes().stream().filter(income -> in.test(income.personId())).toList(),
                today, inflation);
        return new Snapshot(overview, plan, inflation, power, lep, holders, taxInfo().isPresent());
    }

    private fr.patria.ui.Html page(Request request, Notice notice, boolean stocks) {
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        Snapshot snapshot = snapshot(request, java.util.Set.of());
        LocalDate today = LocalDate.now(clock);
        // Historique : le solde du mois en cours de chaque placement visible (graphique de l'epargne).
        snapshot.overview().lines().forEach(line -> savings.recordHistory(line.account().id(), java.time.YearMonth.from(today), line.value()));
        return stocks ? StocksPages.page(SavingsPages.only(snapshot.overview(), true), notice, request.csrf())
                : SavingsPages.page(snapshot, visibility.people(), history(snapshot, today), feeds.offline(), today, notice, request.csrf(),
                        taxForm("", request.csrf()));
    }

    /** 24 derniers mois, empiles : disponible et long terme, hors bourse (dernier solde connu reporte d'un mois sur l'autre). */
    List<SavingsPages.Bar> history(Snapshot snapshot, LocalDate today) {
        java.util.Map<Long, java.util.SortedMap<java.time.YearMonth, BigDecimal>> history = savings.history();
        List<SavingsPages.Bar> bars = new java.util.ArrayList<>();
        java.time.YearMonth current = java.time.YearMonth.from(today);
        for (int back = 23; back >= 0; back--) {
            java.time.YearMonth month = current.minusMonths(back);
            BigDecimal available = BigDecimal.ZERO;
            BigDecimal longTerm = BigDecimal.ZERO;
            for (Savings.Line line : snapshot.overview().lines()) {
                if (line.account().kind().stocks()) continue;
                java.util.SortedMap<java.time.YearMonth, BigDecimal> known = history.getOrDefault(line.account().id(), new java.util.TreeMap<>()).headMap(month.plusMonths(1));
                if (known.isEmpty()) continue;
                BigDecimal value = known.get(known.lastKey());
                if (line.account().kind().available()) available = available.add(value);
                else longTerm = longTerm.add(value);
            }
            bars.add(new SavingsPages.Bar(month, available, longTerm));
        }
        return bars;
    }

    /** Ce qu'affiche l'etape « Epargne et bourse » du guide : comptes, formulaires et import des lignes, avec retour au guide. */
    public fr.patria.ui.Html wizardPanel(Request request, String back) {
        Visibility visibility = Visibility.of(request.viewer(), household.people());
        return SavingsPages.wizard(snapshot(request, java.util.Set.of()), visibility.people(), LocalDate.now(clock), back, request.csrf());
    }

    /** Placement valide depuis la pile « A trier » du guide (avec son versement programme eventuel). */
    public void importAccount(Request request, long person, SavingsKind kind, String name, BigDecimal balance, LocalDate date, BigDecimal monthly) {
        if (!request.viewer().sees(person)) throw new ValidationException("Membre non accessible.");
        LocalDate today = LocalDate.now(clock);
        long id = savings.addAccount(new SavingsRepository.Account(0, person, kind, name, balance, date == null || date.isAfter(today) ? today : date, null, null));
        if (monthly != null && monthly.signum() > 0) {
            long item = budget.insert(new fr.patria.budget.BudgetItem(0, "Versement " + name, fr.patria.budget.Category.TRANSFER, monthly, fr.patria.common.Frequency.MONTHLY,
                    today, null), fr.patria.household.Shares.only(person, household.people()));
            savings.setContribution(id, new SavingsRepository.Contribution(monthly, fr.patria.common.Frequency.MONTHLY, today, item));
        }
    }

    /** Ligne de bourse validee depuis la pile : sur le PEA ou compte-titres de ce nom (ISIN converti si possible). */
    public void importPosition(Request request, String accountName, PositionCsv.Row row) {
        SavingsRepository.Account account = savings.accounts().stream()
                .filter(candidate -> candidate.kind().stocks() && request.viewer().sees(candidate.personId()) && candidate.name().equalsIgnoreCase(accountName.strip()))
                .findFirst().orElseThrow(() -> new ValidationException("validez d'abord le compte « " + accountName + " » (Épargne) pour " + row.label()));
        String symbol = PositionCsv.isIsin(row.code()) ? feeds.resolve(row.code()).orElse(row.code()) : row.symbol();
        java.util.Optional<SavingsRepository.Position> current = savings.positions().stream()
                .filter(position -> position.accountId() == account.id() && position.symbol().equalsIgnoreCase(symbol)).findFirst();
        if (current.isPresent()) savings.updatePosition(current.get().id(), row.quantity(), row.cost());
        else savings.addPosition(new SavingsRepository.Position(0, account.id(), symbol, row.label(), row.quantity(), row.cost(), null));
    }

    /** Nombre de placements et de lignes de bourse visibles (coches automatiques du guide). */
    public int[] counts(Request request) {
        java.util.List<SavingsRepository.Account> accounts = savings.accounts().stream().filter(account -> request.viewer().sees(account.personId())).toList();
        java.util.Set<Long> ids = accounts.stream().map(SavingsRepository.Account::id).collect(java.util.stream.Collectors.toSet());
        return new int[]{accounts.size(), (int) savings.positions().stream().filter(position -> ids.contains(position.accountId())).count()};
    }

    private Response addAccount(Request request) {
        FormData form = request.form();
        try {
            Visibility visibility = Visibility.of(request.viewer(), household.people());
            long person = Long.parseLong(form.raw("person").isBlank() ? "-1" : form.raw("person"));
            if (visibility.people().stream().noneMatch(candidate -> candidate.id() == person)) throw new ValidationException("Choisissez un membre.");
            SavingsKind kind = SavingsKind.parse(form.raw("kind"));
            String name = form.raw("name").isBlank() ? kind.label() : form.requiredText("name", "Nom", 60);
            BigDecimal rate = kind.regulated() || kind == SavingsKind.CURRENT ? null : optionalRate(form.raw("rate"));
            LocalDate opened = form.raw("opened_on").isBlank() ? null : form.date("opened_on", "Date d'ouverture");
            long id = savings.addAccount(new SavingsRepository.Account(0, person, kind, name, form.amount("balance", "Solde"), LocalDate.now(clock), rate, opened));
            BigDecimal monthly = form.optionalAmount("montant", "Versement mensuel");
            if (monthly != null && monthly.signum() > 0) {
                long item = budget.insert(new fr.patria.budget.BudgetItem(0, "Versement " + name, fr.patria.budget.Category.TRANSFER, monthly, fr.patria.common.Frequency.MONTHLY,
                        LocalDate.now(clock), null), fr.patria.household.Shares.only(person, household.people()));
                savings.setContribution(id, new SavingsRepository.Contribution(monthly, fr.patria.common.Frequency.MONTHLY, LocalDate.now(clock), item));
            }
            return done(request, "enregistre", "compte-" + id, kind.stocks());
        } catch (NumberFormatException exception) {
            return Response.invalid(page(request, Notice.error("Choisissez un membre."), stocksForm(form)));
        } catch (ValidationException exception) {
            return Response.invalid(page(request, Notice.error(exception.getMessage()), stocksForm(form)));
        }
    }

    private SavingsRepository.Account editable(Request request) {
        return editable(request, request.id());
    }

    /** Compte d'un membre accessible a la personne connectee (404 sinon, sans dire qu'il existe). */
    public SavingsRepository repository() {
        return savings;
    }

    /** Compte accessible a la personne connectee (404 sinon), pour la page Bourse. */
    SavingsRepository.Account account(Request request, long id) {
        return editable(request, id);
    }

    private SavingsRepository.Account editable(Request request, long id) {
        SavingsRepository.Account account = savings.account(id).orElseThrow(() -> new NotFoundException("Compte introuvable", "Ce compte n'existe pas."));
        if (!request.viewer().sees(account.personId())) throw new NotFoundException("Compte introuvable", "Ce compte n'existe pas.");
        return account;
    }

    static int months(String raw) {
        try {
            return Math.clamp(Integer.parseInt(raw.trim()), 1, 24);
        } catch (NumberFormatException exception) {
            return 6;
        }
    }

    private static BigDecimal optionalRate(String raw) {
        String value = raw.replace(',', '.').replace("%", "").trim();
        if (value.isEmpty()) return null;
        try {
            BigDecimal rate = new BigDecimal(value);
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(30)) > 0) throw new ValidationException("Taux invalide : entre 0 et 30 %.");
            return rate;
        } catch (NumberFormatException exception) {
            throw new ValidationException("Taux invalide : par exemple 2,5.");
        }
    }

    static BigDecimal quantity(String raw) {
        try {
            BigDecimal quantity = new BigDecimal(raw.replace(',', '.').replaceAll("[\\s\\u00a0]", ""));
            if (quantity.signum() <= 0 || quantity.compareTo(BigDecimal.valueOf(10_000_000)) > 0) throw new NumberFormatException();
            return quantity.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw new ValidationException("Quantité invalide : par exemple 12 ou 3,5.");
        }
    }
}
