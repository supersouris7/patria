package fr.patria.savings;

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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Bourse : page des comptes et titres, ajout d'un titre (recherche, puis quantite et prix), import d'un releve de
 * courtier (le compte est cree au besoin), modification et suppression d'un titre, courbes de cours. Les comptes
 * appartiennent a un membre, comme toute l'epargne : l'acces passe par {@link SavingsController#account}.
 */
public final class StocksController {
    private final SavingsController savingsController;
    private final SavingsRepository savings;
    private final HouseholdRepository household;
    private final MarketFeeds feeds;
    private final Clock clock;

    public StocksController(SavingsController savingsController, SavingsRepository savings, HouseholdRepository household, MarketFeeds feeds, Clock clock) {
        this.savingsController = savingsController;
        this.savings = savings;
        this.household = household;
        this.feeds = feeds;
        this.clock = clock;
    }

    public void register(Router router) {
        router.userGet("/bourse", request -> Response.page(StocksPages.page(portfolio(request), request.confirmation(), request.csrf())));
        router.userGet("/bourse/ajouter", request -> Response.page(addPage(request, null)));
        router.userPost("/bourse/ajouter", this::add);
        router.userGet("/bourse/importer", request -> Response.page(StocksPages.importForm(portfolio(request).lines(), holders(request), preselect(request), null,
                request.csrf())));
        router.userPost("/bourse/importer", this::preview);
        router.userPost("/bourse/importer/valider", this::importRows);
        router.userPost("/bourse/ligne/{id}", this::update);
        router.userPost("/bourse/ligne/{id}/supprimer", request -> {
            SavingsRepository.Position position = position(request);
            savings.deletePosition(position.id());
            return Response.redirect("/bourse?ok=supprime#compte-" + position.accountId());
        });
        router.userGet("/bourse/graphique", request -> {
            MarketFeeds.Range range = MarketFeeds.Range.parse(request.query("periode"));
            Savings.Overview portfolio = portfolio(request);
            BigDecimal cash = portfolio.lines().stream().map(Savings.Line::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<SavingsRepository.Position> positions = portfolio.positions().stream().map(Savings.PositionLine::position).toList();
            return Response.page(StockCharts.portfolioChart(StockCharts.portfolio(positions, cash, range, symbol -> feeds.history(symbol, range)), range, "/bourse/graphique"));
        });
        router.userGet("/bourse/ligne/{id}/graphique", request -> {
            SavingsRepository.Position position = position(request);
            MarketFeeds.Range range = MarketFeeds.Range.parse(request.query("periode"));
            return Response.page(StockCharts.position(position, feeds.history(position.symbol(), range), range, "/bourse/ligne/" + position.id() + "/graphique"));
        });
    }

    /** Les seuls comptes de bourse visibles, totaux recalcules. */
    private Savings.Overview portfolio(Request request) {
        return SavingsPages.only(savingsController.snapshot(request, java.util.Set.of()).overview(), true);
    }

    private List<Person> holders(Request request) {
        return Visibility.of(request.viewer(), household.people()).people();
    }

    private static String preselect(Request request) {
        String value = request.query("compte");
        return value != null && value.matches("\\d{1,12}") ? value : null;
    }

    private fr.patria.ui.Html addPage(Request request, Notice notice) {
        String query = request.query("q");
        String symbol = request.query("symbole");
        if (symbol != null) {
            symbol = BrokerExport.yahoo(symbol.strip().toUpperCase(java.util.Locale.ROOT));
            if (!symbol.matches("[A-Z0-9.^=-]{1,20}")) throw new ValidationException("Code invalide : par exemple AI.PA pour Air Liquide à Paris, BTC pour le bitcoin.");
        }
        List<MarketFeeds.Found> results = query == null || query.isBlank() || symbol != null ? List.of() : feeds.search(query);
        return StocksPages.add(portfolio(request).lines(), holders(request), query == null || query.isBlank() ? null : query.strip(), results, feeds.offline(), symbol,
                request.query("nom"), preselect(request), notice, request.csrf());
    }

    /** Ajout d'un titre ; deja present sur ce compte : la quantite et le cout s'ajoutent (nouvel achat). */
    private Response add(Request request) {
        FormData form = request.form();
        try {
            String symbol = BrokerExport.yahoo(form.requiredText("symbole", "Code", 20).strip().toUpperCase(java.util.Locale.ROOT));
            if (!symbol.matches("[A-Z0-9.^=-]{1,20}")) throw new ValidationException("Code invalide.");
            String label = form.raw("nom").isBlank() ? symbol : form.requiredText("nom", "Nom", 60);
            BigDecimal quantity = SavingsController.quantity(form.raw("quantity"));
            BigDecimal amount = form.amount("montant", "Prix d'achat");
            if (amount.signum() <= 0) throw new ValidationException("Prix d'achat : plus de 0 €.");
            BigDecimal cost = ("total".equals(form.raw("prix")) ? amount : amount.multiply(quantity)).setScale(2, RoundingMode.HALF_UP);
            SavingsRepository.Account account = target(request, form);
            java.util.Optional<SavingsRepository.Position> existing = savings.positions().stream()
                    .filter(position -> position.accountId() == account.id() && position.symbol().equalsIgnoreCase(symbol)).findFirst();
            if (existing.isPresent()) savings.updatePosition(existing.get().id(), existing.get().quantity().add(quantity), existing.get().cost().add(cost));
            else savings.addPosition(new SavingsRepository.Position(0, account.id(), symbol, label, quantity, cost, null));
            return Response.redirect("/bourse?ok=enregistre#compte-" + account.id());
        } catch (ValidationException exception) {
            return Response.invalid(addPage(request, Notice.error(exception.getMessage())));
        }
    }

    private Response preview(Request request) {
        FormData form = request.form();
        List<Savings.Line> lines = portfolio(request).lines();
        try {
            String raw = new String(form.uploadedText("csv", "Fichier du courtier ou réponse de l'IA"), StandardCharsets.UTF_8);
            // Export de courtier : converti ici, l'apercu ne transporte que les titres cumules.
            String content = BrokerExport.toLines(raw.startsWith("﻿") ? raw.substring(1) : raw).orElse(raw);
            StocksPages.Target target = new StocksPages.Target(form.raw("compte"), form.raw("compte_type"),
                    form.raw("compte_nom").isBlank() ? form.raw("courtier").strip() : form.raw("compte_nom").strip(), form.raw("titulaire"));
            String name;
            List<SavingsRepository.Position> current;
            if ("nouveau".equals(target.account())) {
                newAccount(request, target, false);
                name = target.name().isBlank() ? "Compte-titres" : target.name();
                current = List.of();
            } else {
                SavingsRepository.Account account = stockAccount(request, target.account());
                name = account.name();
                current = savings.positions().stream().filter(position -> position.accountId() == account.id()).toList();
            }
            List<PositionCsv.Row> rows = PositionCsv.parse(content, current, feeds::resolve);
            return Response.page(StocksPages.preview(name, rows, content, target, request.csrf()));
        } catch (ValidationException exception) {
            return Response.invalid(StocksPages.importForm(lines, holders(request), null, Notice.error("Import impossible. " + exception.getMessage()), request.csrf()));
        }
    }

    private Response importRows(Request request) {
        FormData form = request.form();
        try {
            String content = new String(java.util.Base64.getUrlDecoder().decode(form.raw("contenu")), StandardCharsets.UTF_8);
            SavingsRepository.Account account = target(request, form);
            List<PositionCsv.Row> rows = PositionCsv.parse(content, savings.positions().stream().filter(position -> position.accountId() == account.id()).toList(),
                    feeds::resolve);
            int added = 0;
            for (PositionCsv.Row row : rows) {
                if (!row.fresh() || !"on".equals(form.raw("importer_" + row.line()))) continue;
                if (row.existing() != null) savings.updatePosition(row.existing(), row.quantity(), row.cost());
                else savings.addPosition(new SavingsRepository.Position(0, account.id(), row.symbol(), row.label(), row.quantity(), row.cost(), null));
                added++;
            }
            if (added == 0) throw new ValidationException("Cochez au moins un titre à importer.");
            return Response.redirect("/bourse?ok=importe#compte-" + account.id());
        } catch (ValidationException exception) {
            return Response.invalid(StocksPages.importForm(portfolio(request).lines(), holders(request), null, Notice.error(exception.getMessage()), request.csrf()));
        } catch (IllegalArgumentException exception) {
            return Response.invalid(StocksPages.importForm(portfolio(request).lines(), holders(request), null,
                    Notice.error("Import impossible : contenu illisible, recommencez."), request.csrf()));
        }
    }

    private Response update(Request request) {
        SavingsRepository.Position position = position(request);
        try {
            BigDecimal quantity = SavingsController.quantity(request.form().raw("quantity"));
            BigDecimal average = request.form().amount("pru", "Prix moyen");
            savings.updatePosition(position.id(), quantity, average.multiply(quantity).setScale(2, RoundingMode.HALF_UP));
            return Response.redirect("/bourse?ok=enregistre#compte-" + position.accountId());
        } catch (ValidationException exception) {
            return Response.invalid(StocksPages.page(portfolio(request), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    /** Compte de destination : existant (de bourse, accessible) ou cree a l'instant avec 0 € d'especes. */
    private SavingsRepository.Account target(Request request, FormData form) {
        StocksPages.Target target = new StocksPages.Target(form.raw("compte"), form.raw("compte_type"), form.raw("compte_nom").strip(), form.raw("titulaire"));
        return "nouveau".equals(target.account()) ? newAccount(request, target, true) : stockAccount(request, target.account());
    }

    private SavingsRepository.Account stockAccount(Request request, String id) {
        SavingsRepository.Account account;
        try {
            account = savingsController.account(request, Long.parseLong(id));
        } catch (NumberFormatException exception) {
            throw new ValidationException("Choisissez un compte.");
        }
        if (!account.kind().stocks()) throw new ValidationException("Les titres vont sur un PEA ou un compte-titres.");
        return account;
    }

    /** Verifie le nouveau compte ; {@code create} : l'enregistre (sinon seulement la verification, avant l'apercu). */
    private SavingsRepository.Account newAccount(Request request, StocksPages.Target target, boolean create) {
        long person;
        try {
            person = Long.parseLong(target.holder());
        } catch (NumberFormatException exception) {
            throw new ValidationException("Choisissez le titulaire du compte.");
        }
        if (holders(request).stream().noneMatch(candidate -> candidate.id() == person)) throw new ValidationException("Choisissez le titulaire du compte.");
        SavingsKind kind = "PEA".equals(target.type()) ? SavingsKind.PEA : SavingsKind.SECURITIES;
        String name = target.name().isBlank() ? kind.label() : target.name();
        if (name.length() > 60) throw new ValidationException("Nom du compte : 60 caractères au plus.");
        fr.patria.common.InputRules.checkText(name, "Nom du compte");
        SavingsRepository.Account account = new SavingsRepository.Account(0, person, kind, name, BigDecimal.ZERO, LocalDate.now(clock), null, null);
        if (!create) return account;
        long id = savings.addAccount(account);
        return savings.account(id).orElseThrow();
    }

    private SavingsRepository.Position position(Request request) {
        SavingsRepository.Position position = savings.positions().stream().filter(candidate -> candidate.id() == request.id()).findFirst()
                .orElseThrow(() -> new NotFoundException("Titre introuvable", "Ce titre n'existe pas."));
        savingsController.account(request, position.accountId());
        return position;
    }
}
