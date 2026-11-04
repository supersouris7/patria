package fr.patria.portfolio;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.link;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.sortedCell;
import static fr.patria.ui.Components.tile;

import fr.patria.ui.Components.Line;
import static fr.patria.ui.Format.area;
import static fr.patria.ui.Format.date;
import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;
import static fr.patria.ui.Format.percent;
import static fr.patria.ui.Format.plain;

import fr.patria.contribution.Contribution;
import fr.patria.loan.LoanMath;
import fr.patria.market.HousingRate;
import fr.patria.market.MarketPrice;
import fr.patria.portfolio.Portfolio.LoanSummary;
import fr.patria.portfolio.Portfolio.Overview;
import fr.patria.portfolio.Portfolio.PropertySummary;
import fr.patria.portfolio.Portfolio.Returns;
import fr.patria.portfolio.Portfolio.Revaluation;
import fr.patria.portfolio.Portfolio.SquareMeter;
import fr.patria.property.Property;
import fr.patria.property.PropertyNav;
import fr.patria.property.Valuation;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Pages de synthese : vue d'ensemble, fiche d'un bien, mensualites, administration. Aucun calcul ici. */
final class PortfolioPages {
    private PortfolioPages() {
    }

    // ---- Vue d'ensemble : sommes des biens, sans curseur ----

    static Html dashboard(Overview overview, Optional<HousingRate> rate, String csrf) {
        Html heading = Components.heading("Votre patrimoine immobilier", "Immobilier", null,
                overview.properties().isEmpty() ? Html.EMPTY : Components.pdfButton("/immobilier.pdf"));
        if (overview.properties().isEmpty()) {
            return Layout.page("Immobilier · Patria", Layout.Section.REAL_ESTATE, csrf, Html.concat(heading, Components.emptyState("Aucun bien pour l'instant",
                    "Les biens, prêts et apports s'ajoutent depuis l'administration.", Layout.isAdmin() ? Components.button("/administration/biens", "Aller à l'administration") : Html.EMPTY)));
        }
        Html rows = overview.properties().stream().map(summary -> Html.of("<tr>%s%s%s%s%s%s%s</tr>",
                Html.of("<td><a href=\"/bien/%s\">%s</a><small>%s</small></td>", summary.property().id(), summary.property().name(), summary.property().kind().label()),
                cell(summary.property().commune().isBlank() ? Format.DASH : summary.property().commune()),
                number(summary.property().area(), area(summary.property().area())),
                number(summary.value(), Html.of("%s<small>%s</small>", euroWhole(summary.value()), summary.scenario())),
                number(summary.squareMeter().value(), Format.perSquareMeter(summary.squareMeter().value())),
                marketCell(summary.squareMeter()),
                number(summary.debt(), euroWhole(summary.debt())))).collect(Html.joining());
        Html content = Html.concat(heading,
                Components.metrics(4,
                        tile("Valeur estimée", "Somme des valeurs retenues sur chaque bien. Ajustez-les depuis la fiche de chaque bien.",
                                euroWhole(overview.value()), Format.plural(overview.properties().size(), "bien", "biens")),
                        netTile(overview.net(), "Somme, bien par bien, de la valeur retenue − frais de vente et fiscalité − capital restant dû.",
                                "après " + euroWhole(overview.debt()) + " de capital restant dû"),
                        realGainTile(overview.returns()),
                        nextMonthTile(overview.nextMonth(), null)),
                balance(overview.revaluationPercent() == null ? List.of() : List.of(Line.note("Actualisation des estimations",
                                "Évolution des prix du département (indice DVF) depuis la date d'estimation de chaque bien.", Html.text(Format.signedPercent(overview.revaluationPercent())))),
                        overview.value(), overview.net(), overview.debt(), overview.purchase(), overview.gain(), overview.contributed(), overview.returns()),
                Components.card("Biens", overview.properties().size(), Html.EMPTY,
                        Components.table(List.of("Bien", "Commune", "#Surface", "#Valeur retenue", "#Prix m²", "#Marché m²", "#Capital restant dû"), rows, true, false)),
                Components.card("Prêts", overview.loanCount(), Html.EMPTY, Html.concat(allLoansTable(overview, rate), rateSource(rate))),
                Components.card("Taxes foncières", null, Html.EMPTY, propertyTaxTable(overview.propertyTaxes())));
        return Layout.page("Immobilier · Patria", Layout.Section.REAL_ESTATE, csrf, content);
    }

    // ---- Fiche d'un bien : curseur enregistre en direct ----

    static Html property(PropertySummary summary, Optional<HousingRate> rate, Notice notice, String csrf) {
        Property property = summary.property();
        String subtitle = property.kind().label() + (property.commune().isBlank() ? "" : " · " + property.commune())
                + (property.area() == null ? "" : " · " + area(property.area())) + " · acquis le " + date(property.purchaseDate())
                + (summary.returns().isPrincipalResidence() ? " · résidence principale" : "");
        boolean admin = Layout.isAdmin();
        Html actions = Html.concat(Components.pdfButton("/bien/" + property.id() + "/synthese.pdf"),
                admin ? Components.secondaryButton("/administration/bien/" + property.id(), "Administrer") : Html.EMPTY);
        Html hero = summary.scenarios().isEmpty()
                ? Components.emptyState("Aucune valeur renseignée", "Indiquez le coût d'acquisition ou une estimation pour suivre la valeur de ce bien.",
                        admin ? Components.button("/administration/bien/" + property.id(), "Renseigner") : Html.EMPTY)
                : estimateSlider(summary);
        Html content = Html.concat(
                Components.backLink("/immobilier", "Immobilier"),
                Components.heading("Bien", property.name(), subtitle, actions), Components.notice(notice), alerts(summary), hero,
                Components.metrics(4,
                        netTile(summary.net(), "Valeur retenue − frais de vente et fiscalité (" + Format.ratioPercent(property.saleFeesRate()) + ") − capital restant dû.", null),
                        realGainTile(summary.returns()),
                        tile("Capital restant dû", "Capital restant dû à aujourd'hui, d'après les échéanciers.", euroWhole(summary.debt()),
                                summary.borrowed().signum() == 0 ? null : "sur " + euroWhole(summary.borrowed()) + " empruntés"),
                        nextMonthTile(summary.nextMonth(), "/bien/" + property.id() + "/mensualites")),
                balance(revaluationLines(summary.revaluation()), summary.value(), summary.net(), summary.debt(), property.purchase(), summary.gain(),
                        summary.contributed(), summary.returns()),
                marketCard(summary, csrf),
                Components.card("Prêts", summary.loans().size(), Components.secondaryButton("/bien/" + property.id() + "/mensualites", "Mensualités"),
                        loansTable(summary.loans(), rate)),
                Components.card("Apports", summary.contributions().size(), Html.EMPTY,
                        contributionsTable(summary.contributions(), summary.contributed())));
        return Layout.page(property.name() + " · Patria", Layout.Section.REAL_ESTATE, csrf, content);
    }

    /** Curseur de valeur retenue. Le script {@code app.js} recalcule les tuiles et enregistre la valeur sur le bien. */
    private static Html estimateSlider(PropertySummary summary) {
        Property property = summary.property();
        Valuation.Scenarios s = summary.scenarios();
        String caption = (property.estimateLow() == null ? "Coût d'acquisition du " : "Estimation du ") + date(property.estimateDate()) + ", "
                + revaluationText(property, summary.revaluation(), summary.squareMeter().market()) + ".";
        Html chips = Html.concat(
                chip("Pessimiste", s.pessimistic(), summary.value(), "Estimation prudente − 5 %"),
                chip("Prudent", s.prudent(), summary.value(), "Estimation prudente"),
                chip("Fourchette", s.middle(), summary.value(), "Milieu entre estimations prudente et optimiste"),
                chip("Optimiste", s.optimistic(), summary.value(), "Estimation optimiste"));
        return Html.of("""
                <section class="card hero" data-estimate%s data-fees="%s" data-debt="%s" data-purchase="%s" data-area="%s" data-market="%s"
                data-gain-base="%s" data-invested="%s">
                <div class="hero-head"><div><span class="metric-label">Valeur retenue</span><strong class="hero-value" data-estimate-value>%s</strong>
                <span class="muted">%s <span class="save-state" data-save-state aria-live="polite"></span></span></div>
                <span class="scenario-badge" data-estimate-label>%s</span></div>
                <input class="slider" type="range" min="%s" max="%s" step="1000" value="%s" aria-label="Valeur retenue">
                <div class="chips">%s</div></section>""",
                Layout.isAdmin() ? Html.of(" data-save=\"/bien/%s/valeur\"", property.id()) : Html.EMPTY, plain(property.saleFeesRate()), plain(summary.debt()), plain(property.purchase()),
                plain(property.area()), summary.squareMeter().market() != null && summary.squareMeter().market().isAvailable() ? plain(summary.squareMeter().market().median()) : "",
                plain(gainBase(summary)), plain(summary.returns().invested()),
                euroWhole(summary.value()), caption, summary.scenario(),
                plain(s.sliderMin()), plain(s.sliderMax()), plain(summary.value()), chips);
    }

    private static Html chip(String label, BigDecimal value, BigDecimal current, String hint) {
        return Html.of("<button type=\"button\" class=\"chip%s\" data-scenario=\"%s\" data-value=\"%s\" title=\"%s\"><span>%s</span><b>%s</b></button>",
                Html.raw(value.compareTo(current) == 0 ? " active" : ""), label, plain(value), hint, label, euroWhole(value));
    }

    private static Html loansTable(List<LoanSummary> loans, Optional<HousingRate> rate) {
        if (loans.isEmpty()) return Html.raw("<p class=\"empty\">Aucun prêt pour ce bien.</p>");
        Html rows = loans.stream().map(item -> {
            LoanMath.Status status = item.status();
            return Html.of("<tr>%s%s%s%s%s%s</tr>", cell(link("/pret/" + item.loan().id(), item.loan().name())),
                    number(status.borrowed(), euroWhole(status.borrowed())),
                    number(status.currentRate(), Html.concat(Html.text(percent(status.currentRate())), marketRate(status, rate))),
                    number(status.remaining(), euroWhole(status.remaining())),
                    sortedCell(status.progressPercent(), Html.concat(Components.progress(status.progressPercent()),
                            Html.of("<small>%s restants</small>", Format.duration(status.remainingMonths())))),
                    interestCell(status));
        }).collect(Html.joining());
        Html footer = loans.size() < 2 ? Html.EMPTY : Html.of("<tr><th>Total</th>%s<td></td>%s<td></td>%s</tr>",
                totalCell(loans, LoanMath.Status::borrowed), totalCell(loans, LoanMath.Status::remaining),
                totalInterestCell(loans));
        return Components.table(List.of("Prêt", "#Emprunté", "#Taux actuel", "#Restant dû", "Progression", "#Intérêts"),
                rows, footer, true, false);
    }

    /** Une seule colonne : interets payes, et en dessous le total des interets du pret. Tri sur les interets payes. */
    private static Html interestCell(LoanMath.Status status) {
        return number(status.interestPaid(), interestText(status.interestPaid(), status.interestTotal()));
    }

    private static Html interestText(BigDecimal paid, BigDecimal total) {
        return Html.of("%s payés<small>sur %s au total</small>", euroWhole(paid), euroWhole(total));
    }

    private static Html totalCell(List<LoanSummary> loans, java.util.function.Function<LoanMath.Status, BigDecimal> value) {
        BigDecimal total = loans.stream().map(item -> value.apply(item.status())).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Html.of("<td class=\"num\">%s</td>", euroWhole(total));
    }

    private static Html totalInterestCell(List<LoanSummary> loans) {
        BigDecimal paid = loans.stream().map(item -> item.status().interestPaid()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = loans.stream().map(item -> item.status().interestTotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Html.of("<td class=\"num\">%s</td>", interestText(paid, total));
    }

    /** Taxes foncieres par annee, tous biens confondus, avec l'evolution d'une annee sur l'autre. */
    private static Html propertyTaxTable(List<Portfolio.TaxYear> years) {
        if (years.isEmpty()) {
            return Html.raw("<p class=\"empty\">Aucune taxe foncière saisie. Un seul avis suffit : Administration → un bien → Charges.</p>");
        }
        int visible = 5;
        Html rows = years.stream().map(year -> {
            boolean hidden = years.indexOf(year) >= visible;
            Html origin = year.estimated() == 0 ? Html.raw("<span class=\"badge paid\">Saisie</span>")
                    : year.entered() == 0 ? Html.raw("<span class=\"badge\" title=\"Revalorisée selon le coefficient légal\">Estimée</span>")
                    : Html.of("<span class=\"badge\" title=\"%s\">Mixte</span>", year.entered() + " saisie(s), " + year.estimated() + " estimée(s)");
            Html evolution = year.evolution() == null ? Html.raw("<td class=\"num\">–</td>") : Html.of("<td class=\"num\" data-sort=\"%s\">%s%s</td>",
                    plain(year.evolution()), Format.signedPercent(year.evolution()),
                    year.newProperty() ? Html.raw("<small>nouveau bien</small>") : Html.EMPTY);
            return Html.of("<tr%s>%s%s%s%s%s</tr>", Html.raw(hidden ? " hidden data-more" : ""), cell(year.year()),
                    Html.of("<td class=\"num\">%s</td>", year.properties()), cell(origin), number(year.amount(), euroWhole(year.amount())), evolution);
        }).collect(Html.joining());
        Html more = years.size() <= visible ? Html.EMPTY
                : Html.of("<p class=\"show-more\"><button type=\"button\" class=\"secondary\" data-show-more>Voir tout (%s années)</button></p>", years.size());
        return Html.concat(Components.table(List.of("Année", "#Biens", "Origine", "#Montant", "#Évolution"), rows, true, false), more,
                Html.raw("<p class=\"source\">Montants annuels complets. Années non saisies estimées à partir de l'avis le plus proche, "
                        + "revalorisé selon le coefficient légal des valeurs locatives.</p>"));
    }

    /** Tous les prets du patrimoine, avec le bien finance et la prochaine echeance. */
    private static Html allLoansTable(Overview overview, Optional<HousingRate> rate) {
        if (overview.loanCount() == 0) return Html.raw("<p class=\"empty\">Aucun prêt enregistré.</p>");
        Html rows = overview.properties().stream().flatMap(summary -> summary.loans().stream().map(item -> {
            LoanMath.Status status = item.status();
            var next = status.nextInstallment();
            return Html.of("<tr>%s%s%s%s%s%s%s</tr>",
                    cell(link("/pret/" + item.loan().id(), item.loan().name())),
                    cell(link("/bien/" + summary.property().id(), summary.property().name())),
                    number(status.borrowed(), Html.concat(Html.of("%s<small>à %s</small>", euroWhole(status.borrowed()), percent(status.currentRate())),
                            marketRate(status, rate))),
                    number(status.remaining(), euroWhole(status.remaining())),
                    sortedCell(status.progressPercent(), Html.concat(Components.progress(status.progressPercent()),
                            Html.of("<small>%s restants</small>", Format.duration(status.remainingMonths())))),
                    interestCell(status),
                    next == null ? Html.raw("<td class=\"num\">–</td>")
                            : number(next.amount(), Html.of("%s<small>%s</small>", euroWhole(next.amount()), date(next.date()))));
        })).collect(Html.joining());
        List<LoanSummary> loans = overview.properties().stream().flatMap(summary -> summary.loans().stream()).toList();
        Html footer = Html.of("<tr><th colspan=\"2\">Total</th>%s%s<td></td>%s<td></td></tr>",
                totalCell(loans, LoanMath.Status::borrowed), totalCell(loans, LoanMath.Status::remaining),
                totalInterestCell(loans));
        return Components.table(List.of("Prêt", "Bien", "#Emprunté", "#Restant dû", "Progression",
                "#Intérêts", "#Prochaine échéance"), rows, footer, true, false);
    }

    private static Html contributionsTable(List<Contribution> contributions, BigDecimal total) {
        if (contributions.isEmpty()) return Html.raw("<p class=\"empty\">Aucun apport enregistré.</p>");
        Html rows = contributions.stream().map(item -> Html.of("<tr>%s%s%s</tr>", cell(Layout.isAdmin() ? link("/apport/" + item.id(), item.name()) : Html.text(item.name())),
                sortedCell(item.date(), date(item.date())), number(item.amount(), euro(item.amount())))).collect(Html.joining());
        return Html.of("""
                <div class="table-wrap"><table class="data sortable"><thead><tr><th>Apport</th><th>Date</th><th class="num">Montant</th></tr></thead>
                <tbody>%s</tbody><tfoot><tr><td colspan="2">Total</td><td class="num">%s</td></tr></tfoot></table></div>""", rows, euro(total));
    }

    // ---- Mensualites ----

    static Html months(Property property, List<LoanMath.Month> months, String csrf) {
        LoanMath.Month next = months.stream().filter(month -> !month.paid()).findFirst().orElse(null);
        Html rows = months.stream().map(month -> Html.of("<tr%s>%s%s%s%s</tr>",
                Html.raw(month == next ? " class=\"highlight\"" : ""),
                sortedCell(month.month(), Format.month(month.month())), cell(Components.paidBadge(month.paid())),
                number(month.total(), euro(month.total())),
                cell(month.byLoan().entrySet().stream().map(entry -> Html.of("<span class=\"detail\">%s · %s</span>", entry.getKey(), euro(entry.getValue()))).collect(Html.joining()))))
                .collect(Html.joining());
        Html table = months.isEmpty() ? Html.raw("<p class=\"empty\">Aucune échéance importée.</p>")
                : Components.table(List.of("Mois", "Statut", "#Total", "Par prêt"), rows, true, true);
        Html content = Html.concat(Components.backLink("/bien/" + property.id(), property.name()),
                Components.heading("Mensualités", property.name(), "Total des échéances par mois, tous prêts confondus.", Html.EMPTY),
                Components.card("Échéances", months.size(), Html.EMPTY, table));
        return Layout.page("Mensualités · " + property.name() + " · Patria", Layout.Section.REAL_ESTATE, csrf, content);
    }

    // ---- Administration ----

    static Html administration(Overview overview, Notice notice, String csrf) {
        Html propertyRows = overview.properties().stream().map(summary -> Html.of("<tr>%s%s%s%s%s</tr>",
                cell(link("/bien/" + summary.property().id(), summary.property().name())), cell(summary.property().kind().label()),
                cell(summary.property().commune().isBlank() ? Format.DASH : summary.property().commune()),
                Html.of("<td class=\"num\">%s</td>", summary.loans().size()),
                Html.of("<td class=\"actions\"><a href=\"/administration/bien/%s\">Modifier</a><a href=\"/administration/bien/%s/financements\">Prêts et apports</a></td>",
                        summary.property().id(), summary.property().id()))).collect(Html.joining());
        Html loanRows = overview.properties().stream().flatMap(summary -> summary.loans().stream().map(item -> Html.of("<tr>%s%s%s%s%s%s</tr>",
                cell(link("/pret/" + item.loan().id(), item.loan().name())), cell(summary.property().name()),
                number(item.status().borrowed(), euro(item.status().borrowed())), number(item.status().remaining(), euro(item.status().remaining())),
                number(item.status().currentRate(), percent(item.status().currentRate())),
                Html.of("<td class=\"actions\"><a href=\"/pret/%s/gerer\">Gérer</a></td>", item.loan().id()))))
                .collect(Html.joining());
        Html properties = overview.properties().isEmpty() ? Html.raw("<p class=\"empty\">Aucun bien enregistré.</p>")
                : Components.table(List.of("Bien", "Type", "Commune", "#Prêts", ""), propertyRows, true, false);
        Html loans = overview.loanCount() == 0 ? Html.raw("<p class=\"empty\">Aucun prêt. Ajoutez-en un depuis « Prêts et apports » d'un bien.</p>")
                : Components.table(List.of("Prêt", "Bien", "#Emprunté", "#Restant dû", "#Taux actuel", ""), loanRows, true, false);
        Html content = Html.concat(
                Components.heading("Administration", "Biens et prêts", null, Components.button("/administration/bien/nouveau", "+ Nouveau bien")),
                Components.notice(notice),
                Components.card("Biens", overview.properties().size(), Html.EMPTY, properties),
                Components.card("Prêts", overview.loanCount(), Html.EMPTY, loans));
        return Layout.adminSection("Biens et prêts · Patria", csrf, Layout.AdminSection.PROPERTIES, content);
    }

    /** Prets et apports d'un bien, avec liens de gestion. */
    static Html financing(PropertySummary summary, Notice notice, String csrf) {
        Property property = summary.property();
        Html loanRows = summary.loans().stream().map(item -> Html.of("<tr>%s%s%s%s</tr>",
                cell(link("/pret/" + item.loan().id(), item.loan().name())), number(item.status().borrowed(), euro(item.status().borrowed())),
                number(item.status().remaining(), euro(item.status().remaining())),
                Html.of("<td class=\"actions\"><a href=\"/pret/%s/gerer\">Gérer</a></td>", item.loan().id()))).collect(Html.joining());
        Html contributionRows = summary.contributions().stream().map(item -> Html.of("<tr>%s%s%s%s</tr>",
                cell(item.name()), cell(date(item.date())), number(item.amount(), euro(item.amount())),
                Html.of("<td class=\"actions\"><a href=\"/apport/%s\">Modifier</a></td>", item.id()))).collect(Html.joining());
        Html content = Html.concat(
                Components.heading("Bien", "Prêts et apports", property.name(), Html.EMPTY), Components.notice(notice),
                Components.card("Prêts", summary.loans().size(), Components.button("/bien/" + property.id() + "/prets/nouveau", "+ Ajouter un prêt"),
                        summary.loans().isEmpty() ? Html.raw("<p class=\"empty\">Aucun prêt.</p>")
                                : Components.table(List.of("Prêt", "#Emprunté", "#Restant dû", ""), loanRows, false, false)),
                Components.card("Apports", summary.contributions().size(), Components.button("/bien/" + property.id() + "/apports/nouveau", "+ Ajouter un apport"),
                        summary.contributions().isEmpty() ? Html.raw("<p class=\"empty\">Aucun apport.</p>")
                                : Components.table(List.of("Apport", "Date", "#Montant", ""), contributionRows, false, false)));
        return Layout.adminPage("Prêts et apports · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.FINANCING), content);
    }

    // ---- Prix au m² ----

    /** Carte « Prix au m² » : le bien face aux ventes reelles du secteur (DVF). */
    private static Html marketCard(PropertySummary summary, String csrf) {
        Property property = summary.property();
        boolean admin = Layout.isAdmin();
        SquareMeter sqm = summary.squareMeter();
        MarketPrice market = sqm.market();
        Html refresh = !admin ? Html.EMPTY : Html.of("<form method=\"post\" action=\"/bien/%s/marche\">%s<button class=\"secondary\" type=\"submit\">Actualiser</button></form>",
                property.id(), Components.csrfField(csrf));
        String propertySub = property.area() == null ? "Renseignez la surface du bien" : sqm.purchase() == null ? null : "Acquisition : " + Format.perSquareMeter(sqm.purchase());
        Html propertyTile = Html.of("<article class=\"metric\">%s<strong data-derived=\"sqm\">%s</strong>%s</article>",
                Components.label("Votre bien", "Valeur retenue ÷ surface. Suit le curseur."), Format.perSquareMeter(sqm.value()),
                propertySub == null ? Html.EMPTY : Html.of("<small>%s</small>", propertySub));
        if (property.address().isBlank() && property.commune().isBlank()) {
            return Components.card("Prix au m²", null, Html.EMPTY, Html.concat(Components.strip(propertyTile),
                    admin ? Html.of("<p class=\"muted\">Renseignez l'adresse ou la commune du bien pour le comparer aux ventes du secteur. <a href=\"/administration/bien/%s\">Modifier le bien</a></p>", property.id())
                            : Html.raw("<p class=\"muted\">Adresse du bien non renseignée : pas de comparaison avec les ventes du secteur.</p>")));
        }
        // Pendant un calcul en arriere-plan, app.js interroge l'etat et recharge la page des qu'il est termine.
        Html watcher = sqm.refreshing() ? Html.of("<p class=\"muted pending\" data-market-pending=\"/bien/%s/marche/etat\">%s</p>", property.id(),
                market != null && market.isAvailable() ? "Mise à jour en cours…" : "Calcul en cours à partir des ventes publiques, quelques secondes…") : Html.EMPTY;
        if (market == null || !market.isAvailable()) {
            Html message = market == null || market.isPending() ? Html.EMPTY : Html.of("<p class=\"muted\">%s</p>", market.message());
            return Html.of("<div id=\"marche\">%s</div>", Components.card("Prix au m²", null, sqm.refreshing() ? Html.EMPTY : refresh,
                    Html.concat(Components.strip(propertyTile), watcher, message, source(market))));
        }
        String gapSub = sqm.gapPercent() == null ? null : sqm.gapPercent().signum() > 0 ? "au-dessus du marché" : sqm.gapPercent().signum() < 0 ? "en dessous du marché" : "au prix du marché";
        Html tiles = Components.strip(
                propertyTile,
                tile("Marché", "Prix médian au m² des ventes comparables, actualisé.", Format.perSquareMeter(market.median()),
                        "de " + Format.perSquareMeter(market.low()) + " à " + Format.perSquareMeter(market.high())),
                Html.of("<article class=\"metric\">%s<strong data-derived=\"gap\" class=\"%s\">%s</strong><small data-derived=\"gap-label\">%s</small></article>",
                        Components.label("Écart", "Écart entre le prix au m² de votre bien et la médiane du marché."),
                        sqm.gapPercent() == null ? "" : sqm.gapPercent().signum() > 0 ? "positive" : sqm.gapPercent().signum() < 0 ? "negative" : "",
                        Format.signedPercent(sqm.gapPercent()), gapSub == null ? "" : gapSub),
                tile("Valeur au prix du marché", "Prix médian au m² × surface du bien.", sqm.marketValue() == null ? Format.DASH : euroWhole(sqm.marketValue()),
                        property.area() == null ? "Surface inconnue" : "médiane × " + area(property.area())));
        return Html.of("<div id=\"marche\">%s</div>", Components.card("Prix au m²", null, sqm.refreshing() ? Html.EMPTY : refresh, Html.concat(tiles, watcher, source(market))));
    }

    /** Explication du calcul et de la source, sous la carte. */
    private static Html source(MarketPrice market) {
        if (market == null || market.isPending()) return Html.raw("<p class=\"source\">Source : ventes DVF (DGFiP, Etalab) sur data.gouv.fr.</p>");
        if (!market.isAvailable()) {
            return Html.of("<p class=\"source\">Source : ventes DVF (DGFiP, Etalab) sur data.gouv.fr · vérifié le %s</p>", date(market.computedOn()));
        }
        String adjustment = market.indexMonth() == null ? " (non actualisées : indice du département momentanément indisponible, nouvel essai demain)"
                : ", actualisées à " + Format.month(market.indexMonth()).toLowerCase() + " selon l'évolution des prix du département";
        return Html.of("<p class=\"source\">Médiane de %s ventes de surface comparable %s (%s), %s%s. Valeurs extrêmes écartées.<br>"
                        + "Source : ventes DVF (DGFiP, Etalab) sur data.gouv.fr · calculé le %s, mis à jour tous les 30 jours.</p>"
                        + "<p class=\"market-sales-link\"><a href=\"/bien/%s/marche/ventes\">Voir les %s ventes en détail →</a></p>",
                market.salesCount(), market.scope() == null ? "" : market.scope().label(), market.place(), market.period(), adjustment, date(market.computedOn()),
                market.propertyId(), market.salesCount());
    }

    private static Html marketCell(SquareMeter sqm) {
        MarketPrice market = sqm.market();
        if (market == null || !market.isAvailable()) return Html.raw("<td class=\"num\">–</td>");
        Html gap = sqm.gapPercent() == null ? Html.EMPTY : Html.of("<small class=\"%s\">%s</small>",
                sqm.gapPercent().signum() > 0 ? "positive" : sqm.gapPercent().signum() < 0 ? "negative" : "", Format.signedPercent(sqm.gapPercent()) + " vs marché");
        return Html.of("<td class=\"num\" data-sort=\"%s\">%s%s</td>", plain(market.median()), Format.perSquareMeter(market.median()), gap);
    }

    // ---- Taux du marche ----

    /** « marché 3,19 % · +0,4 pt » sous le taux d'un pret en cours. */
    private static Html marketRate(LoanMath.Status status, Optional<HousingRate> rate) {
        if (rate.isEmpty() || status.currentRate() == null || status.remaining() == null || status.remaining().signum() <= 0) return Html.EMPTY;
        BigDecimal gap = status.currentRate().subtract(rate.get().rate());
        String text = "marché " + percent(rate.get().rate()) + " · " + (gap.signum() > 0 ? "+" : gap.signum() < 0 ? "−" : "")
                + Format.number(gap.abs(), 2) + " pt";
        return Html.of("<small class=\"%s\" title=\"%s\">%s</small>", gap.compareTo(new BigDecimal("0.7")) >= 0 ? "negative" : gap.signum() < 0 ? "positive" : "",
                "Taux moyen des nouveaux crédits immobiliers à taux fixe sur plus de 10 ans (BCE, " + Format.month(rate.get().month()).toLowerCase(java.util.Locale.FRENCH)
                        + "). Écart = votre taux − taux du marché.", text);
    }

    private static Html rateSource(Optional<HousingRate> rate) {
        return rate.isEmpty() ? Html.EMPTY : Html.of("<p class=\"source\">Taux du marché : moyenne des nouveaux crédits immobiliers à taux fixe sur plus de 10 ans "
                + "en France, %s (BCE, données Banque de France), hors assurance. Détail et repère de renégociation sur la fiche de chaque prêt.</p>",
                Format.month(rate.get().month()).toLowerCase(java.util.Locale.FRENCH));
    }

    // ---- Tuiles partagees ----

    private static Html netTile(BigDecimal net, String hint, String note) {
        Html sub = note == null ? Html.EMPTY : Html.of("<small>%s</small>", note);
        return Html.of("<article class=\"metric\">%s<strong data-derived=\"net\">%s</strong>%s</article>", Components.label("Valeur nette indicative", hint), euroWhole(net), sub);
    }


    /** Partie de l'enrichissement reel qui ne depend pas de la valeur : le curseur n'a qu'a ajouter valeur × (1 − frais). */
    private static BigDecimal gainBase(PropertySummary summary) {
        Returns returns = summary.returns();
        return summary.property().purchase().negate().subtract(returns.creditCost()).subtract(returns.expenses()).add(returns.rents()).add(returns.savedRent());
    }

    private static Html realGainTile(Returns returns) {
        String tone = returns.realGain().signum() < 0 ? "negative" : returns.realGain().signum() > 0 ? "positive" : "";
        String multiple = returns.multiple() == null ? "" : Format.multiple(returns.multiple()) + " · ";
        return Html.of("<article class=\"metric\">%s<strong data-derived=\"real-gain\" class=\"%s\">%s</strong><small><span data-derived=\"multiple\">%s</span>%s investis</small></article>",
                Components.label("Enrichissement réel", "Valeur après frais de vente − coût d'acquisition − coût du crédit payé − charges payées + loyers perçus et économisés. "
                        + "Le multiple compare la valeur nette à tout l'argent sorti de votre poche (apport, mensualités, charges, moins les loyers)."),
                tone, Format.signedEuroWhole(returns.realGain()), multiple, euroWhole(returns.invested()));
    }

    /**
     * Carte « Bilan » : detail du calcul de la valeur nette et de l'enrichissement reel, puis les reperes utiles.
     * Les chiffres deja affiches en tuile (valeur, valeur nette, enrichissement reel) y figurent sans mise en valeur.
     * Les valeurs qui dependent de la valeur retenue portent un {@code data-derived} : le curseur les met a jour.
     */
    private static Html balance(List<Line> revaluation, BigDecimal value, BigDecimal net, BigDecimal debt, BigDecimal purchase, BigDecimal gain,
                                BigDecimal contributed, Returns returns) {
        BigDecimal saleFees = value.subtract(net).subtract(debt);
        BigDecimal afterSale = value.subtract(saleFees);
        List<Line> netLines = new java.util.ArrayList<>(revaluation);
        netLines.addAll(List.of(
                Line.lead("Valeur retenue", null, derived("value", euroWhole(value))).echo(),
                Line.of("Frais de vente et fiscalité", returns.isPrincipalResidence()
                        ? "Résidence principale : plus-value exonérée d'impôt, seuls les frais d'agence comptent (environ 5 %). Taux modifiable dans Administrer."
                        : "Pourcentage défini sur chaque bien. Hors résidence principale, inclure l'impôt sur la plus-value (19 % + 17,2 %, réduit selon la durée de détention).",
                        derived("sale-fees", minus(saleFees))),
                Line.of("Capital restant dû", null, Html.text(minus(debt))),
                Line.total("Valeur nette indicative", "Ce que vous toucheriez en vendant aujourd'hui.", derived("net", euroWhole(net))).echo(),
                Line.note("Plus-value latente", "Valeur retenue − coût d'acquisition, hors frais de vente.", signed("gain", gain))));
        Html netBreakdown = Components.breakdown("Valeur nette", netLines);
        Html gainBreakdown = Components.breakdown("Enrichissement réel depuis l'achat", List.of(
                Line.lead("Valeur après frais de vente", null, derived("after-sale", euroWhole(afterSale))),
                Line.of("Coût d'acquisition", "Prix, frais de notaire et travaux.", Html.text(minus(purchase))),
                Line.of("Coût du crédit payé", "Intérêts, assurance et frais des échéances déjà passées.", Html.text(minus(returns.creditCost()))),
                Line.of("Charges payées", "Taxe foncière, copropriété, assurance… Les années non saisies sont estimées.", Html.text(minus(returns.expenses()))),
                Line.of("Loyers perçus", "Loyers hors charges perçus, au jour près.", Html.text(plus(returns.rents()))),
                Line.of("Loyer économisé", "Résidence principale : loyer de référence d'un logement équivalent, que vous n'avez pas eu à payer.",
                        Html.text(plus(returns.savedRent())))
                        .aside(returns.referenceRent().signum() == 0 ? null : euroWhole(returns.referenceRent()) + "/mois"),
                Line.total("Enrichissement réel", null, signed("real-gain", returns.realGain())).echo()));
        String rent = returns.monthlyRent().signum() == 0 ? Format.DASH : euroWhole(returns.monthlyRent()) + "/mois";
        Html landmarks = Components.breakdown("Repères", List.of(
                Line.note("Apports", null, Html.text(euroWhole(contributed))),
                Line.note("Argent investi", "Apport, mensualités et charges payées, moins les loyers perçus et économisés.", Html.text(euroWhole(returns.invested()))),
                Line.note("Multiple", "Valeur nette ÷ argent investi.", Html.text(Format.multiple(returns.multiple()))),
                Line.note("Occupation", "Usage en cours, d'après l'onglet Occupation de chaque bien.", Html.text(returns.occupancy())),
                Line.note("Loyer perçu actuel", null, Html.text(rent)),
                Line.note("Rendement brut", "Loyer annuel actuel ÷ coût d'acquisition (biens loués).", Html.text(Format.percent(returns.grossYield()))),
                Line.note("Charges de l'année", null, Html.text(euroWhole(returns.yearExpenses())))));
        return Components.card("Bilan", null, Html.EMPTY, Html.of("<div class=\"balance\">%s%s%s</div>", netBreakdown, gainBreakdown, landmarks));
    }

    /** « actualisée à juin 2026 : +4,2 % » ou la raison de l'absence d'actualisation. */
    private static String revaluationText(Property property, Revaluation revaluation, MarketPrice market) {
        if (revaluation.factor() != null) {
            String month = market != null && market.indexMonth() != null ? " à " + Format.month(market.indexMonth()).toLowerCase() : "";
            return "actualisée" + month + " : " + Format.signedPercent(revaluation.percent());
        }
        if (property.address().isBlank() && property.commune().isBlank()) return "non actualisée : renseignez la commune du bien";
        if (market == null || market.isPending()) return "actualisation en cours de calcul";
        return "non actualisée (indice des prix du département indisponible)";
    }

    /** Lignes « Valeur estimée le … » et « Actualisation » en tete du detail de la valeur nette. */
    private static List<Line> revaluationLines(Revaluation revaluation) {
        List<Line> lines = new java.util.ArrayList<>();
        lines.add(Line.note("Valeur estimée le " + date(revaluation.since()), "Estimation prudente saisie (ou coût d'acquisition).",
                Html.text(euroWhole(revaluation.base()))));
        lines.add(Line.note("Actualisation", "Évolution des prix du département (indice DVF, même type de bien) depuis cette date.",
                Html.text(revaluation.factor() == null ? "non disponible" : Format.signedPercent(revaluation.percent()))));
        return lines;
    }

    /** Signalements sur la fiche du bien : estimation ancienne, occupation non renseignee. */
    private static Html alerts(PropertySummary summary) {
        Property property = summary.property();
        if (!Layout.isAdmin()) return Html.EMPTY;
        Html outdated = Html.when(summary.estimateOutdated(), Html.of("""
                <p class="notice warning">Estimation du %s, il y a plus de 3 ans : l'actualisation automatique a ses limites.                 <a href="/administration/bien/%s">Mettre à jour l'estimation</a></p>""", date(property.estimateDate()), property.id()));
        Html occupancy = Html.when(summary.occupancyMissing(), Html.of("""
                <p class="notice warning">Occupation non renseignée : l'enrichissement ne compte ni loyer perçu ni loyer économisé.                 <a href="/administration/bien/%s/loyers">Renseigner l'occupation</a></p>""", property.id()));
        return Html.concat(outdated, occupancy);
    }

    /** Montant signe colore (vert/rouge), mis a jour par le curseur. */
    private static Html signed(String name, BigDecimal amount) {
        String tone = amount.signum() > 0 ? "positive" : amount.signum() < 0 ? "negative" : "";
        return Html.of("<span data-derived=\"%s\" class=\"%s\">%s</span>", name, tone, Format.signedEuroWhole(amount));
    }

    private static Html derived(String name, String text) {
        return Html.of("<span data-derived=\"%s\">%s</span>", name, text);
    }

    private static String minus(BigDecimal amount) {
        return amount.signum() == 0 ? euroWhole(amount) : "− " + euroWhole(amount);
    }

    private static String plus(BigDecimal amount) {
        return amount.signum() == 0 ? euroWhole(amount) : "+ " + euroWhole(amount);
    }

    private static Html nextMonthTile(LoanMath.Month next, String detailLink) {
        Html sub = next == null ? Html.text("Aucune échéance à venir")
                : Html.of("%s%s", Format.month(next.month()) + (next.byLoan().size() > 1 ? " · " + next.byLoan().size() + " prêts" : ""),
                        detailLink == null ? Html.EMPTY : Html.of(" · <a href=\"%s\">Détail</a>", detailLink));
        return tile("Prochaine mensualité", "Total des échéances du prochain mois, tous prêts confondus.", next == null ? Format.DASH : euro(next.total()), sub);
    }
}
