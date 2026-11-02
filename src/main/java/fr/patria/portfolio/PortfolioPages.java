package fr.patria.portfolio;

import static fr.patria.ui.Components.cell;
import static fr.patria.ui.Components.link;
import static fr.patria.ui.Components.number;
import static fr.patria.ui.Components.sortedCell;
import static fr.patria.ui.Components.tile;
import static fr.patria.ui.Format.area;
import static fr.patria.ui.Format.date;
import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;
import static fr.patria.ui.Format.percent;
import static fr.patria.ui.Format.plain;

import fr.patria.contribution.Contribution;
import fr.patria.loan.LoanMath;
import fr.patria.portfolio.Portfolio.LoanSummary;
import fr.patria.portfolio.Portfolio.Overview;
import fr.patria.portfolio.Portfolio.PropertySummary;
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

/** Pages de synthese : vue d'ensemble, fiche d'un bien, mensualites, administration. Aucun calcul ici. */
final class PortfolioPages {
    private PortfolioPages() {
    }

    // ---- Vue d'ensemble : sommes des biens, sans curseur ----

    static Html dashboard(Overview overview, String csrf) {
        Html heading = Components.heading("Votre patrimoine immobilier", "Vue d'ensemble", null, Html.EMPTY);
        if (overview.properties().isEmpty()) {
            return Layout.page("Vue d'ensemble · Patria", Layout.Section.OVERVIEW, csrf, Html.concat(heading, Components.emptyState("Aucun bien pour l'instant",
                    "Ajoutez votre premier bien pour suivre sa valeur, ses prêts et vos apports.", Components.button("/administration/bien/nouveau", "+ Ajouter un bien"))));
        }
        Html rows = overview.properties().stream().map(summary -> Html.of("<tr>%s%s%s%s%s</tr>",
                Html.of("<td><a href=\"/bien/%s\">%s</a><small>%s</small></td>", summary.property().id(), summary.property().name(), summary.property().kind().label()),
                cell(summary.property().commune().isBlank() ? Format.DASH : summary.property().commune()),
                number(summary.property().area(), area(summary.property().area())),
                number(summary.value(), Html.of("%s<small>%s</small>", euroWhole(summary.value()), summary.scenario())),
                number(summary.debt(), euroWhole(summary.debt())))).collect(Html.joining());
        Html content = Html.concat(heading,
                Components.metrics(4,
                        tile("Valeur estimée", "Somme des valeurs retenues sur chaque bien. Ajustez-les depuis la fiche de chaque bien.",
                                euroWhole(overview.value()), Format.plural(overview.properties().size(), "bien", "biens")),
                        netTile(overview.net(), "Somme, bien par bien, de la valeur retenue − frais de vente et fiscalité − capital restant dû.", null),
                        gainTile(overview.gain()),
                        tile("Capital restant dû", "Capital restant dû à aujourd'hui sur l'ensemble des prêts, d'après les échéanciers.",
                                euroWhole(overview.debt()), Format.plural(overview.loanCount(), "prêt", "prêts"))),
                Components.metrics(3,
                        nextMonthTile(overview.nextMonth(), null),
                        tile("Coût d'acquisition", "Somme des coûts d'acquisition (prix, frais et travaux).", euroWhole(overview.purchase()), null),
                        tile("Apports", "Somme des apports personnels enregistrés.", euroWhole(overview.contributed()), null)),
                Components.card("Biens", overview.properties().size(), Components.button("/administration/bien/nouveau", "+ Ajouter un bien"),
                        Components.table(List.of("Bien", "Commune", "#Surface", "#Valeur retenue", "#Capital restant dû"), rows, true, false)));
        return Layout.page("Vue d'ensemble · Patria", Layout.Section.OVERVIEW, csrf, content);
    }

    // ---- Fiche d'un bien : curseur enregistre en direct ----

    static Html property(PropertySummary summary, Notice notice, String csrf) {
        Property property = summary.property();
        String subtitle = property.kind().label() + (property.commune().isBlank() ? "" : " · " + property.commune())
                + (property.area() == null ? "" : " · " + area(property.area())) + " · acquis le " + date(property.purchaseDate());
        Html actions = Html.concat(Components.secondaryButton("/administration/bien/" + property.id(), "Modifier"),
                Components.button("/administration/bien/" + property.id() + "/financements", "Prêts et apports"));
        Html hero = summary.scenarios().isEmpty()
                ? Components.emptyState("Aucune valeur renseignée", "Indiquez le coût d'acquisition ou une estimation pour suivre la valeur de ce bien.",
                        Components.button("/administration/bien/" + property.id(), "Renseigner"))
                : estimateSlider(summary);
        String recurring = property.recurringFees().signum() > 0 ? "Frais récurrents : " + euroWhole(property.recurringFees()) + "/an, non déduits" : null;
        Html content = Html.concat(
                Components.backLink("/", "Vue d'ensemble"),
                Components.heading("Bien", property.name(), subtitle, actions), Components.notice(notice), hero,
                Components.metrics(4,
                        netTile(summary.net(), "Valeur retenue − frais de vente et fiscalité (" + Format.ratioPercent(property.saleFeesRate()) + ") − capital restant dû.", recurring),
                        gainTile(summary.gain()),
                        tile("Capital restant dû", "Capital restant dû à aujourd'hui, d'après les échéanciers.", euroWhole(summary.debt()), null),
                        nextMonthTile(summary.nextMonth(), "/bien/" + property.id() + "/mensualites")),
                Components.metrics(4,
                        tile("Coût d'acquisition", "Montant total engagé pour acheter le bien : prix, frais et travaux.", euroWhole(property.purchase()), date(property.purchaseDate())),
                        tile("Capital emprunté", "Total des déblocages des prêts.", euroWhole(summary.borrowed()), null),
                        tile("Capital remboursé", "Part de capital des échéances déjà passées.", euroWhole(summary.repaid()), null),
                        tile("Apports", "Somme des apports personnels enregistrés pour ce bien.", euroWhole(summary.contributed()), null)),
                Components.card("Prêts", summary.loans().size(), Html.concat(
                        Components.secondaryButton("/bien/" + property.id() + "/mensualites", "Mensualités"),
                        Components.button("/bien/" + property.id() + "/prets/nouveau", "+ Ajouter")), loansTable(summary.loans())),
                Components.card("Apports", summary.contributions().size(), Components.button("/bien/" + property.id() + "/apports/nouveau", "+ Ajouter"),
                        contributionsTable(summary.contributions(), summary.contributed())));
        return Layout.page(property.name() + " · Patria", Layout.Section.OVERVIEW, csrf, content);
    }

    /** Curseur de valeur retenue. Le script {@code app.js} recalcule les tuiles et enregistre la valeur sur le bien. */
    private static Html estimateSlider(PropertySummary summary) {
        Property property = summary.property();
        Valuation.Scenarios s = summary.scenarios();
        String caption = property.estimateLow() == null ? "Aucune estimation : le coût d'acquisition sert de base." : "Estimation du " + date(property.estimateDate()) + ".";
        Html chips = Html.concat(
                chip("Pessimiste", s.pessimistic(), summary.value(), "Estimation prudente − 5 %"),
                chip("Prudent", s.prudent(), summary.value(), "Estimation prudente"),
                chip("Fourchette", s.middle(), summary.value(), "Milieu entre estimations prudente et optimiste"),
                chip("Optimiste", s.optimistic(), summary.value(), "Estimation optimiste"));
        return Html.of("""
                <section class="card hero" data-estimate data-save="/bien/%s/valeur" data-fees="%s" data-debt="%s" data-purchase="%s">
                <div class="hero-head"><div><span class="metric-label">Valeur retenue</span><strong class="hero-value" data-estimate-value>%s</strong>
                <span class="muted">%s <span class="save-state" data-save-state aria-live="polite"></span></span></div>
                <span class="scenario-badge" data-estimate-label>%s</span></div>
                <input class="slider" type="range" min="%s" max="%s" step="10000" value="%s" aria-label="Valeur retenue">
                <div class="chips">%s</div></section>""",
                property.id(), plain(property.saleFeesRate()), plain(summary.debt()), plain(property.purchase()),
                euroWhole(summary.value()), caption, summary.scenario(),
                plain(s.sliderMin()), plain(s.sliderMax()), plain(summary.value()), chips);
    }

    private static Html chip(String label, BigDecimal value, BigDecimal current, String hint) {
        return Html.of("<button type=\"button\" class=\"chip%s\" data-scenario=\"%s\" data-value=\"%s\" title=\"%s\"><span>%s</span><b>%s</b></button>",
                Html.raw(value.compareTo(current) == 0 ? " active" : ""), label, plain(value), hint, label, euroWhole(value));
    }

    private static Html loansTable(List<LoanSummary> loans) {
        if (loans.isEmpty()) return Html.raw("<p class=\"empty\">Aucun prêt pour ce bien.</p>");
        Html rows = loans.stream().map(item -> {
            LoanMath.Status status = item.status();
            return Html.of("<tr>%s%s%s%s%s%s</tr>", cell(link("/pret/" + item.loan().id(), item.loan().name())),
                    number(status.borrowed(), euro(status.borrowed())), number(status.currentRate(), percent(status.currentRate())),
                    number(status.remaining(), euro(status.remaining())),
                    sortedCell(status.progressPercent(), Components.progress(status.progressPercent())),
                    sortedCell(status.remainingMonths(), Format.duration(status.remainingMonths())));
        }).collect(Html.joining());
        return Components.table(List.of("Prêt", "#Emprunté", "#Taux actuel", "#Restant dû", "Progression", "Durée restante"), rows, true, false);
    }

    private static Html contributionsTable(List<Contribution> contributions, BigDecimal total) {
        if (contributions.isEmpty()) return Html.raw("<p class=\"empty\">Aucun apport enregistré.</p>");
        Html rows = contributions.stream().map(item -> Html.of("<tr>%s%s%s</tr>", cell(link("/apport/" + item.id(), item.name())),
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
        return Layout.page("Mensualités · " + property.name() + " · Patria", Layout.Section.OVERVIEW, csrf, content);
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
        return Layout.page("Administration · Patria", Layout.Section.ADMINISTRATION, csrf, content);
    }

    /** Prets et apports d'un bien, avec liens de gestion. */
    static Html financing(PropertySummary summary, String csrf) {
        Property property = summary.property();
        Html loanRows = summary.loans().stream().map(item -> Html.of("<tr>%s%s%s%s</tr>",
                cell(link("/pret/" + item.loan().id(), item.loan().name())), number(item.status().borrowed(), euro(item.status().borrowed())),
                number(item.status().remaining(), euro(item.status().remaining())),
                Html.of("<td class=\"actions\"><a href=\"/pret/%s/gerer\">Gérer</a></td>", item.loan().id()))).collect(Html.joining());
        Html contributionRows = summary.contributions().stream().map(item -> Html.of("<tr>%s%s%s%s</tr>",
                cell(item.name()), cell(date(item.date())), number(item.amount(), euro(item.amount())),
                Html.of("<td class=\"actions\"><a href=\"/apport/%s\">Modifier</a></td>", item.id()))).collect(Html.joining());
        Html content = Html.concat(
                Components.heading("Bien", "Prêts et apports", property.name(), Html.EMPTY),
                Components.card("Prêts", summary.loans().size(), Components.button("/bien/" + property.id() + "/prets/nouveau", "+ Ajouter un prêt"),
                        summary.loans().isEmpty() ? Html.raw("<p class=\"empty\">Aucun prêt.</p>")
                                : Components.table(List.of("Prêt", "#Emprunté", "#Restant dû", ""), loanRows, false, false)),
                Components.card("Apports", summary.contributions().size(), Components.button("/bien/" + property.id() + "/apports/nouveau", "+ Ajouter un apport"),
                        summary.contributions().isEmpty() ? Html.raw("<p class=\"empty\">Aucun apport.</p>")
                                : Components.table(List.of("Apport", "Date", "#Montant", ""), contributionRows, false, false)));
        return Layout.adminPage("Prêts et apports · " + property.name() + " · Patria", csrf, PropertyNav.of(property, PropertyNav.Tab.FINANCING), content);
    }

    // ---- Tuiles partagees ----

    private static Html netTile(BigDecimal net, String hint, String note) {
        Html sub = note == null ? Html.EMPTY : Html.of("<small>%s</small>", note);
        return Html.of("<article class=\"metric\">%s<strong data-derived=\"net\">%s</strong>%s</article>", Components.label("Valeur nette indicative", hint), euroWhole(net), sub);
    }

    private static Html gainTile(BigDecimal gain) {
        String tone = gain.signum() < 0 ? "negative" : gain.signum() > 0 ? "positive" : "";
        return Html.of("<article class=\"metric\">%s<strong data-derived=\"gain\" class=\"%s\">%s</strong></article>",
                Components.label("Plus-value latente", "Valeur retenue − coût d'acquisition. Hors frais de vente et fiscalité."), tone, Format.signedEuroWhole(gain));
    }

    private static Html nextMonthTile(LoanMath.Month next, String detailLink) {
        Html sub = next == null ? Html.text("Aucune échéance à venir")
                : Html.of("%s%s", Format.month(next.month()) + (next.byLoan().size() > 1 ? " · " + next.byLoan().size() + " prêts" : ""),
                        detailLink == null ? Html.EMPTY : Html.of(" · <a href=\"%s\">Détail</a>", detailLink));
        return tile("Prochaine mensualité", "Total des échéances du prochain mois, tous prêts confondus.", next == null ? Format.DASH : euro(next.total()), sub);
    }
}
