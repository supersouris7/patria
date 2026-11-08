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

    /** Un mois du graphique : epargne disponible, long terme et bourse. */
    record Bar(java.time.YearMonth month, BigDecimal available, BigDecimal longTerm, BigDecimal stocks) {
        BigDecimal total() {
            return available.add(longTerm).add(stocks);
        }
    }

    static Html page(SavingsController.Snapshot snapshot, List<Person> people, List<Bar> bars, boolean offline, LocalDate today, Notice notice, String csrf) {
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
                Components.notice(notice), tiles, chart(bars), planCard(snapshot, csrf), accounts(overview, today, null, csrf), addAccount(people, null, csrf),
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

    /** Jeton CSRF et, depuis le guide, la page ou revenir apres l'action. */
    private static Html token(String csrf, String back) {
        return back == null ? Components.csrfField(csrf) : Html.concat(Components.csrfField(csrf), Html.of("<input type=\"hidden\" name=\"retour\" value=\"%s\">", back));
    }

    static Html accounts(Savings.Overview overview, LocalDate today, String back, String csrf) {
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
                    <button class="secondary" type="submit">Mettre à jour</button></form>""", account.id(), token(csrf, back), account.balance().toPlainString(), account.name());
            Html delete = Html.of("""
                    <form method="post" action="/epargne/compte/%s/supprimer" class="inline-form" data-confirm="Supprimer « %s » et ses lignes ?">%s\
                    <button class="danger" type="submit">Supprimer</button></form>""", account.id(), account.name(), token(csrf, back));
            Html main = Html.of("""
                    <tr id="compte-%s"><td><b>%s</b><small>%s · %s%s</small></td><td class="num">%s<small>solde du %s</small></td><td class="num">%s</td><td class="num">%s</td><td>%s%s</td></tr>""",
                    account.id(), account.name(), account.kind().label(), line.owner(), fill, euroWhole(line.value()), Format.date(account.balanceDate()),
                    line.rate() == null ? "—" : Format.percent(line.rate()), line.yearlyInterest() == null ? "—" : euroWhole(line.yearlyInterest()), update, delete);
            Html contribution = contribution(line, back, csrf);
            return account.kind().stocks() ? Html.concat(main, contribution, positions(line, back, csrf)) : Html.concat(main, contribution);
        }).collect(Html.joining());
        Html footer = Html.of("<tr><th>Total</th><th class=\"num\">%s</th><th></th><th class=\"num\">%s</th><th></th></tr>", euroWhole(overview.total()),
                euroWhole(overview.lines().stream().map(Savings.Line::yearlyInterest).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return Components.card("Comptes", overview.lines().size(), Html.EMPTY,
                Components.table(List.of("Placement", "#Valeur", "#Taux", "#Intérêts / an", ""), rows, footer, false, false));
    }

    /** Lignes de bourse d'un PEA ou compte-titres, et le formulaire d'ajout. */
    private static Html positions(Savings.Line line, String back, String csrf) {
        long account = line.account().id();
        Html rows = line.positions().stream().map(position -> Html.of("""
                <li><b>%s</b> <code>%s</code> · %s × %s (%s) = <b>%s</b> · <span class="%s">%s</span>\
                <form method="post" action="/epargne/ligne/%s/supprimer" class="inline-form">%s<button class="link-button" type="submit" aria-label="Supprimer %s">×</button></form></li>""",
                position.position().label(), position.position().symbol(), position.position().quantity().toPlainString(), Format.euro(position.price()), position.priceSource(),
                euroWhole(position.value()), position.gain().signum() >= 0 ? "positive" : "negative", Format.signedEuroWhole(position.gain()) + " de plus-value",
                position.position().id(), token(csrf, back), position.position().label())).collect(Html.joining());
        Html paste = Html.of("""
                <details><summary>Importer les lignes depuis l'export du courtier (Trade Republic, Boursorama…)</summary>%s
                <form method="post" action="/epargne/compte/%s/import" class="stack" enctype="multipart/form-data">%s%s<label>Réponse de l'IA (lignes code;nom;quantité;prix de revient)
                <textarea name="texte" rows="6" placeholder="IE00B4L5Y983;ETF MSCI World;12,4;1050,30"></textarea></label>
                <div class="form-actions"><button type="submit">Voir l'aperçu</button></div></form></details>""",
                Components.llmPrompt("prompt-bourse-" + account, PositionCsv.llmPrompt()), account, token(csrf, back), Components.localAiInput("bourse"));
        Html add = Html.of("""
                <details><summary>Ajouter une ligne</summary><form method="post" action="/epargne/compte/%s/ligne" class="stack">%s%s
                <p class="muted small">Code Yahoo Finance : le symbole suivi de la place, par ex. AI.PA (Air Liquide, Paris), CW8.PA (ETF MSCI World). Le cours du jour
                est récupéré automatiquement ; sinon saisissez-le.</p><div class="form-actions"><button type="submit">Ajouter la ligne</button></div></form></details>""",
                account, token(csrf, back), Components.fields(3,
                        Components.input(Input.of("Code Yahoo Finance", "symbol", Field.TEXT).required().maxLength(20).placeholder("AI.PA"), Map.of()),
                        Components.input(Input.of("Nom", "label", Field.TEXT).maxLength(60).placeholder("Air Liquide"), Map.of()),
                        Components.input(Input.of("Quantité", "quantity", Field.NUMBER).required(), Map.of()),
                        Components.input(Input.of("Prix de revient total", "cost", Field.MONEY).required(), Map.of()),
                        Components.input(Input.of("Cours (facultatif)", "price", Field.MONEY).help("Vide : cours du jour"), Map.of())));
        return Html.of("<tr class=\"positions\"><td colspan=\"5\"><ul class=\"plain-list\">%s</ul>%s%s</td></tr>", rows, paste, add);
    }

    /** Versement programme : resume s'il existe, formulaire pour le poser ou le changer. */
    private static Html contribution(Savings.Line line, String back, String csrf) {
        SavingsRepository.Account account = line.account();
        SavingsRepository.Contribution current = account.contribution();
        Html summary = current == null ? Html.raw("<span class=\"muted\">Pas de versement programmé.</span>") : Html.of("""
                <span><b>Versement programmé : %s</b> (%s, depuis le %s)%s · repris dans le budget (Virements).</span>""",
                euroWhole(current.amount()), current.frequency().label().toLowerCase(java.util.Locale.FRENCH), Format.date(current.start()),
                line.added().signum() > 0 ? " · " + Format.signedEuroWhole(line.added()) + " ajoutés depuis le solde du " + Format.date(account.balanceDate()) : "");
        List<Components.Option> frequencies = Arrays.stream(fr.patria.common.Frequency.values()).filter(frequency -> frequency != fr.patria.common.Frequency.ONCE)
                .map(frequency -> new Components.Option(frequency.name(), frequency.label())).toList();
        Html form = Html.of("""
                <details><summary>%s</summary><form method="post" action="/epargne/compte/%s/versement" class="row-form">%s
                <label>Montant<input name="montant" inputmode="decimal" value="%s" required size="8"></label>%s
                <label>Premier versement<input name="debut" type="date" value="%s"></label><button class="secondary" type="submit">Enregistrer</button></form>%s</details>""",
                current == null ? "Programmer un versement" : "Modifier le versement", account.id(), token(csrf, back),
                current == null ? "" : current.amount().toPlainString(),
                Components.select("Fréquence", "frequence", frequencies, current == null ? "MONTHLY" : current.frequency().name()),
                current == null ? "" : current.start().toString(),
                current == null ? Html.EMPTY : Html.of("""
                        <form method="post" action="/epargne/compte/%s/versement" class="inline-form">%s<input type="hidden" name="supprimer" value="1">\
                        <button class="danger" type="submit">Arrêter le versement</button></form>""", account.id(), token(csrf, back)));
        return Html.of("<tr class=\"contribution\"><td colspan=\"5\">%s %s</td></tr>", summary, form);
    }

    /** Taux officiels du jour, pour pre-remplir le champ taux selon le type choisi ({@code KIND=taux;...}). */
    private static String regulatedRates() {
        StringBuilder rates = new StringBuilder();
        for (SavingsKind kind : SavingsKind.values()) {
            if (!kind.regulated()) continue;
            RegulatedRates.at(kind == SavingsKind.LIVRET_JEUNE ? SavingsKind.LIVRET_A : kind, LocalDate.now())
                    .ifPresent(rate -> rates.append(kind.name()).append('=').append(rate.percent().toPlainString().replace('.', ',')).append(';'));
        }
        return rates.toString();
    }

    /**
     * Historique de l'epargne : barres empilees sur 24 mois (disponible, bourse, long terme), legende, infobulle par
     * segment et tableau des valeurs. SVG dessine cote serveur.
     */
    static Html chart(List<Bar> bars) {
        if (bars.stream().allMatch(bar -> bar.total().signum() == 0)) return Html.EMPTY;
        BigDecimal max = bars.stream().map(Bar::total).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal top = niceCeiling(max);
        int width = 720;
        int height = 200;
        int left = 56;
        int bottom = 24;
        double plot = height - bottom - 8;
        double slot = (width - left - 8) / (double) bars.size();
        double barWidth = Math.max(6, slot - 6);
        StringBuilder svg = new StringBuilder();
        for (int tick = 0; tick <= 2; tick++) {
            BigDecimal value = top.multiply(BigDecimal.valueOf(tick)).divide(BigDecimal.valueOf(2), 0, java.math.RoundingMode.HALF_UP);
            double y = 8 + plot - plot * tick / 2.0;
            svg.append(String.format(java.util.Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/>", left, width - 8, y, y));
            svg.append(String.format(java.util.Locale.ROOT, "<text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>", left - 6, y + 4, escape(compact(value))));
        }
        for (int index = 0; index < bars.size(); index++) {
            Bar bar = bars.get(index);
            double x = left + index * slot + (slot - barWidth) / 2;
            double y = 8 + plot;
            String month = Format.month(bar.month());
            for (Object[] part : new Object[][]{{bar.available(), "available", "Disponible"}, {bar.stocks(), "stocks", "Bourse"}, {bar.longTerm(), "long", "Long terme"}}) {
                BigDecimal value = (BigDecimal) part[0];
                if (value.signum() <= 0) continue;
                double h = plot * value.doubleValue() / top.doubleValue();
                y -= h;
                svg.append(String.format(java.util.Locale.ROOT, "<rect class=\"seg %s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2\"><title>%s · %s : %s</title></rect>",
                        part[1], x, y, barWidth, Math.max(0, h - 2), escape(month), part[2], escape(euroWhole(value))));
            }
            if (index % 6 == 0 || index == bars.size() - 1) {
                svg.append(String.format(java.util.Locale.ROOT, "<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>", x + barWidth / 2, height - 6,
                        escape(Format.shortMonth(bar.month()) + " " + String.format("%02d", bar.month().getYear() % 100))));
            }
        }
        Html rows = bars.stream().filter(bar -> bar.total().signum() > 0).map(bar -> Html.of("<tr><td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td></tr>",
                Format.month(bar.month()), euroWhole(bar.available()), euroWhole(bar.stocks()), euroWhole(bar.longTerm()), euroWhole(bar.total()))).collect(Html.joining());
        return Components.card("Historique de l'épargne", null, Html.EMPTY, Html.of("""
                <ul class="chart-legend"><li><i class="available"></i>Disponible (comptes, livrets)</li><li><i class="stocks"></i>Bourse (PEA, comptes-titres)</li>\
                <li><i class="long"></i>Long terme (assurance-vie, PER, PEL)</li></ul>
                <svg class="savings-chart" viewBox="0 0 %s %s" role="img" aria-label="Épargne des 24 derniers mois, par type">%s</svg>
                <p class="muted small">Un mois sans relevé reprend le dernier solde connu. L'historique se remplit à chaque mise à jour et chaque mois.</p>
                <details><summary>Voir les valeurs</summary>%s</details>""", width, height, Html.raw(svg.toString()),
                Components.table(List.of("Mois", "#Disponible", "#Bourse", "#Long terme", "#Total"), rows, false, false)));
    }

    private static BigDecimal niceCeiling(BigDecimal max) {
        double value = Math.max(1, max.doubleValue());
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double[] steps = {1, 2, 2.5, 5, 10};
        for (double step : steps) if (step * magnitude >= value) return BigDecimal.valueOf(step * magnitude);
        return BigDecimal.valueOf(10 * magnitude);
    }

    private static String compact(BigDecimal value) {
        return value.compareTo(BigDecimal.valueOf(1000)) >= 0 ? Format.number(value.divide(BigDecimal.valueOf(1000), 1, java.math.RoundingMode.HALF_UP), 1) + " k€" : euroWhole(value);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Apercu de l'import des lignes de bourse : une case par ligne valide et nouvelle. */
    static Html importPreview(SavingsRepository.Account account, List<PositionCsv.Row> rows, String content, String back, String csrf) {
        long fresh = rows.stream().filter(PositionCsv.Row::fresh).count();
        Html body = rows.stream().map(row -> row.valid() ? Html.of("<tr><td>%s</td><td>%s</td><td><code>%s</code>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td>%s</td></tr>",
                row.fresh() ? Html.of("<input type=\"checkbox\" name=\"importer_%s\" value=\"on\" form=\"positions-import\" checked aria-label=\"Importer %s\">", row.line(), row.label())
                        : Html.text(String.valueOf(row.line())),
                row.label(), row.symbol(), row.symbol().equals(row.code()) ? Html.EMPTY : Html.of("<small>%s</small>", row.code()),
                Format.number(row.quantity(), 6), euroWhole(row.cost()),
                row.duplicate() ? Html.raw("<span class=\"badge\">Déjà sur ce compte</span>") : PositionCsv.isIsin(row.symbol())
                        ? Html.raw("<span class=\"badge warn\" title=\"Code de cotation introuvable : comptée au prix de revient\">Sans cours</span>")
                        : Html.raw("<span class=\"badge paid\">À importer</span>"))
                : Html.of("<tr class=\"row-error\"><td>%s</td><td colspan=\"5\" class=\"negative\">%s</td></tr>", row.line(), row.error())).collect(Html.joining());
        String encoded = java.util.Base64.getUrlEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Html actions = fresh == 0 ? Html.raw("<p class=\"muted\">Rien de nouveau à importer.</p>") : Html.of("""
                <form id="positions-import" method="post" action="/epargne/compte/%s/import/valider" class="form-actions">%s<input type="hidden" name="contenu" value="%s">\
                <a class="button secondary" href="%s">Annuler</a><button type="submit">Importer la sélection</button></form>""",
                account.id(), token(csrf, back), encoded, back == null ? "/epargne" : back);
        Html content2 = Html.concat(Components.backLink(back == null ? "/epargne" : back, back == null ? "Épargne" : "Retour au guide"),
                Components.heading("Épargne", "Aperçu de l'import", fresh + " ligne" + (fresh > 1 ? "s" : "") + " à importer sur « " + account.name() + " ». Les lignes en "
                        + "erreur sont ignorées.", Html.EMPTY),
                Components.card("Lignes", rows.size(), Html.EMPTY, Html.concat(Components.table(List.of("Importer", "Titre", "Code", "#Quantité", "#Prix de revient", "État"), body, false, false),
                        Html.raw("<p class=\"muted small\">« Sans cours » : le code ISIN n'a pas pu être converti en code de cotation (mode hors ligne ou titre introuvable) ; "
                                + "la ligne est comptée à son prix de revient, modifiable ensuite.</p>"), actions)));
        return Layout.page("Import · Épargne · Patria", Layout.Section.SAVINGS, csrf, content2);
    }

    /** Etape « Epargne et bourse » du guide : comptes, versements, lignes et import, avec retour au guide. */
    static Html wizard(SavingsController.Snapshot snapshot, List<Person> people, LocalDate today, String back, String csrf) {
        Savings.Overview overview = snapshot.overview();
        boolean stocks = overview.lines().stream().anyMatch(line -> line.account().kind().stocks());
        Html hint = stocks ? Html.EMPTY : Html.raw("<p class=\"muted\">Pour importer vos lignes de bourse, ajoutez d'abord votre PEA ou compte-titres ci-dessous (type « PEA » ou "
                + "« Compte-titres ») : l'import apparaîtra sous ce compte.</p>");
        return Html.of("<div id=\"import\">%s%s%s</div>", accounts(overview, today, back, csrf), hint, addAccount(people, back, csrf));
    }

    static Html addAccount(List<Person> people, String back, String csrf) {
        if (people.isEmpty()) return Html.EMPTY;
        List<Components.Option> owners = people.stream().map(person -> new Components.Option(String.valueOf(person.id()), person.name())).toList();
        List<Components.Option> kinds = Arrays.stream(SavingsKind.values()).map(kind -> new Components.Option(kind.name(), kind.label())).toList();
        List<Html> fields = new ArrayList<>(List.of(Components.select("Membre", "person", owners, owners.getFirst().value()),
                Html.concat(Components.select("Type", "kind", kinds, SavingsKind.LIVRET_A.name()), Html.of("<span hidden data-regulated-rates=\"%s\"></span>", regulatedRates())),
                Components.input(Input.of("Nom", "name", Field.TEXT).maxLength(60).placeholder("Livret A de la Banque Postale"), Map.of()),
                Components.input(Input.of("Solde actuel", "balance", Field.MONEY).required(), Map.of()),
                Components.input(Input.of("Taux annuel (%)", "rate", Field.TEXT).maxLength(6).placeholder("1,7")
                        .help("Rempli d'office pour un livret réglementé (taux officiel) ; à saisir pour une assurance-vie ou un livret bancaire"), Map.of()),
                Components.input(Input.of("Date d'ouverture", "opened_on", Field.DATE).help("Utile pour un PEA (5 ans)"), Map.of())));
        fields.add(Components.input(Input.of("Versement mensuel (facultatif)", "montant", Field.MONEY).help("Virement programmé : le solde avancera tout seul"), Map.of()));
        return Components.disclosure("Ajouter un placement", Components.form("/epargne/compte", csrf, false, Html.concat(back == null ? Html.EMPTY
                        : Html.of("<input type=\"hidden\" name=\"retour\" value=\"%s\">", back), Components.fields(3, fields.toArray(Html[]::new))),
                "Ajouter le placement"), back != null);
    }
}
