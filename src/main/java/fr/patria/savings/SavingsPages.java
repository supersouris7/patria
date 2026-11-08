package fr.patria.savings;

import static fr.patria.ui.Format.euroWhole;

import fr.patria.household.Person;
import fr.patria.ui.Components;
import fr.patria.ui.Components.Field;
import fr.patria.ui.Components.Input;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Page Epargne : tuiles, plan d'epargne de securite, comptes et lignes de bourse, formulaires. */
final class SavingsPages {
    private SavingsPages() {
    }

    static Html page(SavingsController.Snapshot snapshot, List<Person> people, boolean offline, LocalDate today, Notice notice, String csrf) {
        Savings.Overview overview = snapshot.overview();
        Savings.Plan plan = snapshot.plan();
        String inflation = snapshot.inflation().map(value -> Format.signedPercent(value.percent())).orElse(offline ? "Hors ligne" : "…");
        String inflationSub = snapshot.inflation().map(value -> "sur 12 mois, " + Format.month(value.month()) + " (INSEE)").orElse(offline ? "mode hors ligne" : "chargement…");
        String power = snapshot.power().map(value -> Format.signedPercent(value.power())).orElse("—");
        String powerSub = snapshot.power().map(value -> "revenus " + Format.signedPercent(value.income()) + " en un an, prix " + Format.signedPercent(value.inflation()))
                .orElse("revenus d'il y a un an inconnus ou inflation indisponible");
        Html tiles = Components.metrics(4,
                Components.tile("Épargne totale", "Somme des soldes saisis et de la valeur des lignes de bourse.", euroWhole(overview.total()),
                        overview.stocks().signum() == 0 ? null : "dont bourse " + euroWhole(overview.stocks())),
                Components.tile("Épargne de précaution", "Argent disponible tout de suite : comptes courants et livrets. Repère : 3 à 6 mois de dépenses.",
                        euroWhole(overview.available()), plan.spending().signum() == 0 ? null : Format.number(plan.coverMonths(), 1) + " mois de dépenses"),
                Components.tile("Inflation", "Hausse des prix sur 12 mois, indice des prix à la consommation de l'INSEE.", inflation, inflationSub),
                Components.tile("Pouvoir d'achat", "Évolution de vos revenus sur un an, corrigée de l'inflation. Positif : vos revenus progressent plus vite que les prix.",
                        power, powerSub));
        Html content = Html.concat(Components.heading("Votre épargne", "Épargne", "Livrets, assurance-vie, PEA et comptes-titres de chacun, avec un plan pour sécuriser le foyer.",
                        Html.EMPTY),
                Components.notice(notice), tiles, planCard(snapshot, csrf), accounts(overview, today, csrf), addAccount(people, csrf),
                Html.of("<p class=\"muted small\">Taux des livrets réglementés : %s, vérifiés le %s. Cours de bourse : Yahoo Finance, en euros, rafraîchis toutes les heures. "
                        + "Indicatif : ce n'est pas un conseil en investissement.</p>", RegulatedRates.SOURCE, Format.date(RegulatedRates.CHECKED)));
        return Layout.page("Épargne · Patria", Layout.Section.SAVINGS, csrf, content);
    }

    private static Html planCard(SavingsController.Snapshot snapshot, String csrf) {
        Savings.Plan plan = snapshot.plan();
        Html summary;
        if (plan.spending().signum() == 0) {
            summary = Html.raw("<p class=\"muted\">Saisissez vos dépenses (Gérer → Dépenses) : l'objectif se calcule à partir de vos dépenses mensuelles.</p>");
        } else if (plan.missing().signum() == 0) {
            summary = Html.of("<p class=\"notice success\">Objectif atteint : %s de côté pour %s mois de dépenses (%s). La suite peut viser le long terme.</p>",
                    euroWhole(plan.available()), plan.months(), euroWhole(plan.target()));
        } else {
            summary = Html.of("""
                    <p>Objectif : <b>%s</b> (%s mois de dépenses à %s). Vous avez %s : il manque <b>%s</b>.
                    %s</p>""", euroWhole(plan.target()), plan.months(), euroWhole(plan.spending()), euroWhole(plan.available()), euroWhole(plan.missing()),
                    plan.monthly().signum() > 0 ? Html.of("Versement conseillé : <b>%s par mois</b> (environ un an, sans dépasser la moitié de ce qui vous reste chaque mois).",
                            euroWhole(plan.monthly()))
                            : Html.raw("Votre budget ne dégage pas de reste chaque mois : commencez par le Prévisionnel et le Bilan."));
        }
        Html rows = plan.steps().stream().map(step -> Html.of("<tr><td><b>%s</b></td><td class=\"num\">%s</td><td class=\"num\">%s</td><td>%s</td></tr>", step.where(),
                step.amount() == null ? "—" : euroWhole(step.amount()), step.rate() == null ? "—" : Format.percent(step.rate()), step.why())).collect(Html.joining());
        String lep = snapshot.lep();
        Html settings = Html.of("""
                <form method="post" action="/epargne/plan" class="row-form">%s%s%s<button class="secondary" type="submit">Recalculer</button></form>""",
                Components.csrfField(csrf),
                Components.select("Éligible au LEP ?", "lep", List.of(new Components.Option("", "Je ne sais pas"), new Components.Option("oui", "Oui"),
                        new Components.Option("non", "Non")), lep),
                Components.select("Objectif de précaution", "mois", List.of(new Components.Option("3", "3 mois"), new Components.Option("4", "4 mois"),
                        new Components.Option("6", "6 mois"), new Components.Option("9", "9 mois"), new Components.Option("12", "12 mois")), String.valueOf(plan.months())));
        return Html.of("<div id=\"plan\">%s</div>", Components.card("Plan d'épargne de sécurité", null, Html.EMPTY, Html.concat(summary,
                Html.raw("<p class=\"muted\">Ordre de remplissage conseillé : d'abord une épargne disponible et sans risque, puis le long terme.</p>"),
                Components.table(List.of("Où", "#Montant", "#Taux", "Pourquoi"), rows, false, false), settings)));
    }

    private static Html accounts(Savings.Overview overview, LocalDate today, String csrf) {
        if (overview.isEmpty()) {
            return Components.card("Comptes", 0, Html.EMPTY, Html.raw("<p class=\"empty\">Aucun placement saisi. Ajoutez vos livrets, assurances-vie et PEA ci-dessous.</p>"));
        }
        Html rows = overview.lines().stream().map(line -> {
            SavingsRepository.Account account = line.account();
            BigDecimal ceiling = account.kind().ceiling();
            String fill = ceiling == null || account.kind().stocks() ? "" : " · " + account.balance().multiply(BigDecimal.valueOf(100)).divide(ceiling, 0, java.math.RoundingMode.HALF_UP)
                    + " % du plafond";
            Html update = Html.of("""
                    <form method="post" action="/epargne/compte/%s" class="inline-form">%s<input name="balance" inputmode="decimal" value="%s" aria-label="Nouveau solde de %s" size="9">\
                    <button class="secondary" type="submit">Mettre à jour</button></form>""", account.id(), Components.csrfField(csrf), account.balance().toPlainString(), account.name());
            Html delete = Html.of("""
                    <form method="post" action="/epargne/compte/%s/supprimer" class="inline-form" data-confirm="Supprimer « %s » et ses lignes ?">%s\
                    <button class="danger" type="submit">Supprimer</button></form>""", account.id(), account.name(), Components.csrfField(csrf));
            Html main = Html.of("""
                    <tr id="compte-%s"><td><b>%s</b><small>%s · %s%s</small></td><td class="num">%s<small>solde du %s</small></td><td class="num">%s</td><td class="num">%s</td><td>%s%s</td></tr>""",
                    account.id(), account.name(), account.kind().label(), line.owner(), fill, euroWhole(line.value()), Format.date(account.balanceDate()),
                    line.rate() == null ? "—" : Format.percent(line.rate()), line.yearlyInterest() == null ? "—" : euroWhole(line.yearlyInterest()), update, delete);
            return account.kind().stocks() ? Html.concat(main, positions(line, csrf)) : main;
        }).collect(Html.joining());
        Html footer = Html.of("<tr><th>Total</th><th class=\"num\">%s</th><th></th><th class=\"num\">%s</th><th></th></tr>", euroWhole(overview.total()),
                euroWhole(overview.lines().stream().map(Savings.Line::yearlyInterest).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return Components.card("Comptes", overview.lines().size(), Html.EMPTY,
                Components.table(List.of("Placement", "#Valeur", "#Taux", "#Intérêts / an", ""), rows, footer, false, false));
    }

    /** Lignes de bourse d'un PEA ou compte-titres, et le formulaire d'ajout. */
    private static Html positions(Savings.Line line, String csrf) {
        long account = line.account().id();
        Html rows = line.positions().stream().map(position -> Html.of("""
                <li><b>%s</b> <code>%s</code> · %s × %s (%s) = <b>%s</b> · <span class="%s">%s</span>\
                <form method="post" action="/epargne/ligne/%s/supprimer" class="inline-form">%s<button class="link-button" type="submit" aria-label="Supprimer %s">×</button></form></li>""",
                position.position().label(), position.position().symbol(), position.position().quantity().toPlainString(), Format.euro(position.price()), position.priceSource(),
                euroWhole(position.value()), position.gain().signum() >= 0 ? "positive" : "negative", Format.signedEuroWhole(position.gain()) + " de plus-value",
                position.position().id(), Components.csrfField(csrf), position.position().label())).collect(Html.joining());
        Html add = Html.of("""
                <details><summary>Ajouter une ligne</summary><form method="post" action="/epargne/compte/%s/ligne" class="stack">%s%s
                <p class="muted small">Code Yahoo Finance : le symbole suivi de la place, par ex. AI.PA (Air Liquide, Paris), CW8.PA (ETF MSCI World). Le cours du jour
                est récupéré automatiquement ; sinon saisissez-le.</p><div class="form-actions"><button type="submit">Ajouter la ligne</button></div></form></details>""",
                account, Components.csrfField(csrf), Components.fields(3,
                        Components.input(Input.of("Code Yahoo Finance", "symbol", Field.TEXT).required().maxLength(20).placeholder("AI.PA"), Map.of()),
                        Components.input(Input.of("Nom", "label", Field.TEXT).maxLength(60).placeholder("Air Liquide"), Map.of()),
                        Components.input(Input.of("Quantité", "quantity", Field.NUMBER).required(), Map.of()),
                        Components.input(Input.of("Prix de revient total", "cost", Field.MONEY).required(), Map.of()),
                        Components.input(Input.of("Cours (facultatif)", "price", Field.MONEY).help("Vide : cours du jour"), Map.of())));
        return Html.of("<tr class=\"positions\"><td colspan=\"5\"><ul class=\"plain-list\">%s</ul>%s</td></tr>", rows, add);
    }

    private static Html addAccount(List<Person> people, String csrf) {
        if (people.isEmpty()) return Html.EMPTY;
        List<Components.Option> owners = people.stream().map(person -> new Components.Option(String.valueOf(person.id()), person.name())).toList();
        List<Components.Option> kinds = Arrays.stream(SavingsKind.values()).map(kind -> new Components.Option(kind.name(), kind.label())).toList();
        List<Html> fields = new ArrayList<>(List.of(Components.select("Membre", "person", owners, owners.getFirst().value()),
                Components.select("Type", "kind", kinds, SavingsKind.LIVRET_A.name()),
                Components.input(Input.of("Nom", "name", Field.TEXT).maxLength(60).placeholder("Livret A de la Banque Postale"), Map.of()),
                Components.input(Input.of("Solde actuel", "balance", Field.MONEY).required(), Map.of()),
                Components.input(Input.of("Taux annuel (%)", "rate", Field.TEXT).maxLength(6).help("Assurance-vie, livret bancaire ; vide pour un livret réglementé"), Map.of()),
                Components.input(Input.of("Date d'ouverture", "opened_on", Field.DATE).help("Utile pour un PEA (5 ans)"), Map.of())));
        return Components.disclosure("Ajouter un placement", Components.form("/epargne/compte", csrf, false, Components.fields(3, fields.toArray(Html[]::new)),
                "Ajouter le placement"), false);
    }
}
