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
    record Bar(java.time.YearMonth month, BigDecimal available, BigDecimal longTerm) {
        BigDecimal total() {
            return available.add(longTerm);
        }
    }

    static Html page(SavingsController.Snapshot snapshot, List<Person> people, List<Bar> bars, boolean offline, LocalDate today, Notice notice, String csrf) {
        Savings.Overview overview = snapshot.overview();
        Savings.Plan plan = snapshot.plan();
        String inflation = snapshot.inflation().map(value -> Format.signedPercent(value.percent())).orElse(offline ? "Hors ligne" : "…");
        String inflationSub = snapshot.inflation().map(value -> "sur 12 mois · INSEE").orElse(offline ? "mode hors ligne" : "chargement…");
        String power = snapshot.power().map(value -> Format.signedPercent(value.power())).orElse("—");
        String powerSub = snapshot.power().map(value -> value.estimated() ? "à revenus constants" : "revenus " + Format.signedPercent(value.income()) + " en un an")
                .orElse("revenus à saisir");
        BigDecimal months = plan.coverMonths();
        String cushionTone = plan.spending().signum() == 0 ? "" : months.compareTo(BigDecimal.ONE) < 0 ? "negative" : months.compareTo(BigDecimal.valueOf(3)) < 0 ? "warning" : "positive";
        Savings.Overview saved = only(overview, false);
        Html tiles = Components.metrics(4,
                tile("Épargne totale", "Comptes, livrets, assurance-vie, PER et PEL. La bourse a sa propre page.", euroWhole(saved.total()), "accent",
                        overview.stocks().signum() == 0 ? null : "hors bourse (" + euroWhole(overview.stocks()) + ")"),
                tile("Précaution", "Disponible tout de suite (comptes, livrets). Repère : 3 à 6 mois de dépenses.", euroWhole(overview.available()), cushionTone,
                        plan.spending().signum() == 0 ? null : Format.number(months, 1) + " mois de dépenses"),
                tile("Inflation", "Hausse des prix sur 12 mois (INSEE).", inflation, snapshot.inflation().isPresent() ? "warning" : "", inflationSub),
                tile("Pouvoir d'achat", "Revenus d'il y a un an comparés à aujourd'hui, corrigés de l'inflation ; à défaut, revenus supposés constants.", power,
                        snapshot.power().map(value -> value.power().signum() >= 0 ? "positive" : "negative").orElse(""), powerSub));
        Html content = Html.concat(Components.heading("Votre épargne", "Épargne", "Comptes, livrets et placements à capital garanti ou long terme.", Html.EMPTY),
                Components.notice(notice), tiles, accounts(saved, today, null, csrf), addAccount(people, null, csrf), planCard(snapshot, csrf), chart(bars),
                Html.of("<p class=\"muted small\">Taux réglementés : %s, au %s. Indicatif, pas un conseil en investissement.</p>", RegulatedRates.SOURCE,
                        Format.date(RegulatedRates.CHECKED)));
        return Layout.page("Épargne · Patria", Layout.Section.SAVINGS, csrf, content);
    }

    /** Page Bourse : PEA, comptes-titres et cryptos au cours du jour, separes de l'epargne (risque de perte, horizon long). */
    /** Les seuls comptes de bourse ({@code stocks}) ou d'epargne, totaux recalcules. */
    static Savings.Overview only(Savings.Overview overview, boolean stocks) {
        List<Savings.Line> lines = overview.lines().stream().filter(line -> line.account().kind().stocks() == stocks).toList();
        BigDecimal total = lines.stream().map(Savings.Line::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gain = lines.stream().flatMap(line -> line.positions().stream()).map(Savings.PositionLine::gain).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Savings.Overview(lines, total, stocks ? BigDecimal.ZERO : overview.available(), stocks ? total : BigDecimal.ZERO, stocks ? gain : BigDecimal.ZERO);
    }

    /** Tuile dont la valeur est mise en couleur ({@code tone} : positive, negative, warning, accent). */
    private static Html tile(String label, String hint, String value, String tone, String sub) {
        return Html.of("<article class=\"metric\">%s<strong class=\"%s\">%s</strong>%s</article>", Components.label(label, hint), tone, value,
                sub == null ? Html.EMPTY : Html.of("<small>%s</small>", sub));
    }

    private static Html planCard(SavingsController.Snapshot snapshot, String csrf) {
        Savings.Plan plan = snapshot.plan();
        Html summary;
        if (plan.spending().signum() == 0) {
            summary = Html.raw("<p class=\"muted\">Saisissez vos dépenses pour calculer l'objectif.</p>");
        } else if (plan.missing().signum() == 0) {
            summary = Html.of("<p class=\"plan-goal done\"><b>Objectif atteint</b> · %s pour %s mois de dépenses</p>", euroWhole(plan.available()), plan.months());
        } else {
            summary = Html.of("<p class=\"plan-goal\">Objectif <b>%s</b> · il manque <b class=\"negative\">%s</b>%s</p>", euroWhole(plan.target()), euroWhole(plan.missing()),
                    plan.monthly().signum() > 0 ? Html.of(" · versez <b class=\"positive\">%s par mois</b>", euroWhole(plan.monthly())) : Html.EMPTY);
        }
        Html rows = plan.steps().stream().map(step -> Html.of("<tr><td><b>%s</b><small>%s</small></td><td class=\"num\">%s</td><td class=\"num\">%s</td></tr>", step.where(),
                step.why(), step.amount() == null ? "—" : euroWhole(step.amount()), step.rate() == null ? "—" : Format.percent(step.rate()))).collect(Html.joining());
        String lep = snapshot.lep();
        Html settings = Html.of("""
                <form method="post" action="/epargne/plan" class="row-form">%s%s%s<button class="secondary" type="submit">Recalculer</button></form>""",
                Components.csrfField(csrf),
                Components.select("Éligible au LEP ?", "lep", List.of(new Components.Option("", "Je ne sais pas"), new Components.Option("oui", "Oui"),
                        new Components.Option("non", "Non")), lep),
                Components.select("Objectif de précaution", "mois", List.of(new Components.Option("3", "3 mois"), new Components.Option("4", "4 mois"),
                        new Components.Option("6", "6 mois"), new Components.Option("9", "9 mois"), new Components.Option("12", "12 mois")), String.valueOf(plan.months())));
        return Html.of("<div id=\"plan\">%s</div>", Components.card("Plan d'épargne de sécurité", null, Html.EMPTY, Html.concat(summary,
                Components.table(List.of("Dans l'ordre", "#Montant", "#Taux"), rows, false, false), settings)));
    }

    /** Jeton CSRF et, depuis le guide, la page ou revenir apres l'action. */
    private static Html token(String csrf, String back) {
        return back == null ? Components.csrfField(csrf) : Html.concat(Components.csrfField(csrf), Html.of("<input type=\"hidden\" name=\"retour\" value=\"%s\">", back));
    }

    /** Tableau des placements (page Epargne et etape du guide) ; un compte de bourse montre ses titres, geres sur la page Bourse. */
    static Html accounts(Savings.Overview overview, LocalDate today, String back, String csrf) {
        if (overview.isEmpty()) {
            return Components.card("Comptes", 0, Html.EMPTY, Html.raw("<p class=\"empty\">Aucun placement pour l'instant.</p>"));
        }
        Html rows = overview.lines().stream().map(line -> {
            SavingsRepository.Account account = line.account();
            BigDecimal ceiling = account.kind().ceiling();
            int fill = ceiling == null || account.kind().stocks() ? -1
                    : line.balance().multiply(BigDecimal.valueOf(100)).divide(ceiling, 0, java.math.RoundingMode.HALF_UP).min(BigDecimal.valueOf(100)).intValue();
            SavingsRepository.Contribution contribution = account.contribution();
            Html badges = Html.concat(
                    contribution == null ? Html.EMPTY : Html.of("<span class=\"badge positive-badge\" title=\"Versement programmé\">+%s / %s</span>", euroWhole(contribution.amount()),
                            contribution.frequency() == fr.patria.common.Frequency.MONTHLY ? "mois" : contribution.frequency().label().toLowerCase(java.util.Locale.FRENCH)),
                    fill < 0 ? Html.EMPTY : Html.of("<span class=\"ceiling\" title=\"%s du plafond\"><i style=\"width:%s\"></i></span>", fill + " %", fill + "%"));
            Html main = Html.of("""
                    <tr id="compte-%s"><td><b>%s</b><small>%s · %s</small>%s</td><td class="num"><b>%s</b></td><td class="num">%s</td><td class="num positive">%s</td><td class="actions">%s</td></tr>""",
                    account.id(), account.name(), account.kind().label(), line.owner(), badges, euroWhole(line.value()),
                    line.rate() == null ? "—" : Format.percent(line.rate()),
                    line.yearlyInterest() == null || line.yearlyInterest().signum() == 0 ? "—" : "+" + euroWhole(line.yearlyInterest()),
                    manage(line, back, csrf));
            return account.kind().stocks() ? Html.concat(main, positions(line)) : main;
        }).collect(Html.joining());
        BigDecimal interest = overview.lines().stream().map(Savings.Line::yearlyInterest).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Html footer = Html.of("<tr><th>Total</th><th class=\"num\">%s</th><th></th><th class=\"num positive\">+%s</th><th></th></tr>", euroWhole(overview.total()), euroWhole(interest));
        return Components.card("Comptes", overview.lines().size(), Html.EMPTY,
                Html.of("<div class=\"table-wrap\"><table class=\"data savings-table\"><thead><tr><th>Placement</th><th class=\"num\">Valeur</th><th class=\"num\">Taux</th>"
                        + "<th class=\"num\">Intérêts / an</th><th></th></tr></thead><tbody>%s</tbody><tfoot>%s</tfoot></table></div>", rows, footer));
    }

    /** Titres d'un PEA ou compte-titres, en lecture : ajout, import et modification se font sur la page Bourse. */
    private static Html positions(Savings.Line line) {
        Html rows = line.positions().stream().map(position -> Html.of("<tr><td>%s <code>%s</code></td><td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num %s\">%s</td></tr>",
                position.position().label(), position.position().symbol(), Format.number(position.position().quantity(), 4), euroWhole(position.value()),
                StocksPages.tone(position.gain()), Format.signedEuroWhole(position.gain()))).collect(Html.joining());
        Html table = line.positions().isEmpty() ? Html.EMPTY : Html.of("""
                <table class="data positions-table"><thead><tr><th>Titre</th><th class="num">Quantité</th><th class="num">Valeur</th><th class="num">Plus-value</th></tr></thead>                <tbody>%s</tbody></table>""", rows);
        return Html.of("<tr class=\"positions\"><td colspan=\"5\">%s<p class=\"muted small\"><a href=\"/bourse/ajouter?compte=%s\">Ajouter un titre</a> · "
                + "<a href=\"/bourse/importer?compte=%s\">Importer un relevé</a> (page Bourse)</p></td></tr>", table, line.account().id(), line.account().id());
    }

    /** Menu « Gerer » d'un compte : nouveau solde, versement programme, suppression. */
    static Html manage(Savings.Line line, String back, String csrf) {
        SavingsRepository.Account account = line.account();
        SavingsRepository.Contribution current = account.contribution();
        List<Components.Option> frequencies = Arrays.stream(fr.patria.common.Frequency.values()).filter(frequency -> frequency != fr.patria.common.Frequency.ONCE)
                .map(frequency -> new Components.Option(frequency.name(), frequency.label())).toList();
        return Html.of("""
                <details class="row-menu"><summary>Gérer</summary><div class="row-menu-body">
                <form method="post" action="/epargne/compte/%s" class="row-form">%s<label>%s<input name="balance" inputmode="decimal" value="%s"></label>\
                <button class="secondary" type="submit">Mettre à jour</button></form>
                <form method="post" action="/epargne/compte/%s/versement" class="row-form">%s<label>Versement programmé<input name="montant" inputmode="decimal" value="%s"></label>%s\
                <button class="secondary" type="submit">Enregistrer</button></form>%s
                <form method="post" action="/epargne/compte/%s/supprimer" data-confirm="Supprimer « %s » et ses lignes ?">%s<button class="danger" type="submit">Supprimer le compte</button></form>
                </div></details>""",
                account.id(), token(csrf, back), account.kind().stocks() ? "Espèces non investies" : "Solde du jour", line.balance().toPlainString(),
                account.id(), token(csrf, back), current == null ? "" : current.amount().toPlainString(),
                Components.select("Fréquence", "frequence", frequencies, current == null ? "MONTHLY" : current.frequency().name()),
                current == null ? Html.EMPTY : Html.of("""
                        <form method="post" action="/epargne/compte/%s/versement">%s<input type="hidden" name="supprimer" value="1"><button class="quiet" type="submit">Arrêter le versement</button></form>""",
                        account.id(), token(csrf, back)),
                account.id(), account.name(), token(csrf, back));
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
            for (Object[] part : new Object[][]{{bar.available(), "available", "Disponible"}, {bar.longTerm(), "long", "Long terme"}}) {
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
        Html rows = bars.stream().filter(bar -> bar.total().signum() > 0).map(bar -> Html.of("<tr><td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td></tr>",
                Format.month(bar.month()), euroWhole(bar.available()), euroWhole(bar.longTerm()), euroWhole(bar.total()))).collect(Html.joining());
        return Components.card("Historique de l'épargne", null, Html.EMPTY, Html.of("""
                <ul class="chart-legend"><li><i class="available"></i>Disponible (comptes, livrets)</li><li><i class="long"></i>Long terme (assurance-vie, PER, PEL)</li></ul>
                <svg class="savings-chart" viewBox="0 0 %s %s" role="img" aria-label="Épargne des 24 derniers mois, par type">%s</svg>
                <p class="muted small">Mois sans relevé : dernier solde connu.</p>
                <details><summary>Voir les valeurs</summary>%s</details>""", width, height, Html.raw(svg.toString()),
                Components.table(List.of("Mois", "#Disponible", "#Long terme", "#Total"), rows, false, false)));
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

    /** Etape « Epargne et bourse » du guide : comptes, versements, lignes et import, avec retour au guide. */
    static Html wizard(SavingsController.Snapshot snapshot, List<Person> people, LocalDate today, String back, String csrf) {
        Savings.Overview overview = snapshot.overview();
        Html hint = Html.raw("<p class=\"muted\">Bourse et cryptos (PEA, compte-titres) : sur la page <a href=\"/bourse\">Bourse</a>, « Importer un relevé » "
                + "crée le compte et ses titres d'un coup.</p>");
        return Html.of("<div id=\"import\">%s%s%s</div>", accounts(overview, today, back, csrf), hint, addAccount(people, back, csrf));
    }

    /** Nouveau placement d'epargne (les comptes de bourse naissent sur la page Bourse, a l'ajout d'un titre ou a l'import). */
    static Html addAccount(List<Person> people, String back, String csrf) {
        if (people.isEmpty()) return Html.EMPTY;
        List<Components.Option> owners = people.stream().map(person -> new Components.Option(String.valueOf(person.id()), person.name())).toList();
        List<Components.Option> kinds = Arrays.stream(SavingsKind.values()).filter(kind -> !kind.stocks())
                .map(kind -> new Components.Option(kind.name(), kind.label())).toList();
        List<Html> fields = new ArrayList<>(List.of(Components.select("Membre", "person", owners, owners.getFirst().value()),
                Html.concat(Components.select("Type", "kind", kinds, kinds.getFirst().value()), Html.of("<span hidden data-regulated-rates=\"%s\"></span>", regulatedRates())),
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
