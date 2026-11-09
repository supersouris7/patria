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

    /** Tirelire rose (illustration decorative, sans texte) : une piece tombe dans la fente. */
    static final String PIGGY = """
            <svg class="piggy" viewBox="0 0 128 104" aria-hidden="true" focusable="false">
            <defs><linearGradient id="piggy-body" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffc8dd"/><stop offset="1" stop-color="#f28db5"/></linearGradient></defs>
            <g class="coin"><circle cx="64" cy="14" r="10"/><text x="64" y="18.5" text-anchor="middle">€</text></g>
            <path class="tail" d="M20 60 c-9 -2 -10 -12 -3 -13 c5 0 5 7 0 8"/>
            <rect class="leg" x="34" y="78" width="13" height="18" rx="6"/><rect class="leg" x="76" y="78" width="13" height="18" rx="6"/>
            <path class="ear" d="M42 40 Q44 22 60 34 Z"/>
            <rect x="18" y="34" width="88" height="54" rx="27" fill="url(#piggy-body)"/>
            <rect class="slot" x="54" y="36" width="20" height="5" rx="2.5"/>
            <circle class="cheek" cx="88" cy="68" r="7"/>
            <rect class="snout" x="98" y="52" width="18" height="20" rx="8"/>
            <ellipse class="nostril" cx="104" cy="62" rx="1.8" ry="2.6"/><ellipse class="nostril" cx="110" cy="62" rx="1.8" ry="2.6"/>
            <circle class="eye" cx="86" cy="52" r="3.6"/><circle class="eye-light" cx="87.3" cy="50.7" r="1.2"/>
            </svg>""";

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
        Html tiles = Components.metrics(4,
                tile("Épargne totale", "Soldes et valeur des lignes de bourse.", euroWhole(overview.total()), "accent",
                        overview.stocks().signum() == 0 ? null : "dont bourse " + euroWhole(overview.stocks())),
                tile("Précaution", "Disponible tout de suite (comptes, livrets). Repère : 3 à 6 mois de dépenses.", euroWhole(overview.available()), cushionTone,
                        plan.spending().signum() == 0 ? null : Format.number(months, 1) + " mois de dépenses"),
                tile("Inflation", "Hausse des prix sur 12 mois (INSEE).", inflation, snapshot.inflation().isPresent() ? "warning" : "", inflationSub),
                tile("Pouvoir d'achat", "Revenus d'il y a un an comparés à aujourd'hui, corrigés de l'inflation ; à défaut, revenus supposés constants.", power,
                        snapshot.power().map(value -> value.power().signum() >= 0 ? "positive" : "negative").orElse(""), powerSub));
        Html content = Html.concat(Components.heading("Votre épargne", "Épargne", "Livrets, placements et bourse du foyer.", Html.raw(PIGGY)),
                Components.notice(notice), tiles, chart(bars), planCard(snapshot, csrf), accounts(overview, today, null, csrf), addAccount(people, null, csrf),
                Html.of("<p class=\"muted small\">Taux réglementés : %s, au %s. Indicatif, pas un conseil en investissement.</p>", RegulatedRates.SOURCE,
                        Format.date(RegulatedRates.CHECKED)));
        return Layout.page("Épargne · Patria", Layout.Section.SAVINGS, csrf, content);
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
                    line.rate() == null ? "—" : Format.percent(line.rate()), line.yearlyInterest() == null || line.yearlyInterest().signum() == 0 ? "—" : "+" + euroWhole(line.yearlyInterest()),
                    manage(line, back, csrf));
            return account.kind().stocks() ? Html.concat(main, positions(line, back, csrf)) : main;
        }).collect(Html.joining());
        BigDecimal interest = overview.lines().stream().map(Savings.Line::yearlyInterest).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Html footer = Html.of("<tr><th>Total</th><th class=\"num\">%s</th><th></th><th class=\"num positive\">+%s</th><th></th></tr>", euroWhole(overview.total()), euroWhole(interest));
        return Components.card("Comptes", overview.lines().size(), Html.EMPTY,
                Html.of("<div class=\"table-wrap\"><table class=\"data savings-table\"><thead><tr><th>Placement</th><th class=\"num\">Valeur</th><th class=\"num\">Taux</th>"
                        + "<th class=\"num\">Intérêts / an</th><th></th></tr></thead><tbody>%s</tbody><tfoot>%s</tfoot></table></div>", rows, footer));
    }

    /** Lignes de bourse d'un PEA ou compte-titres : tableau aligne, plus-values en couleur, ajout et import replies. */
    private static Html positions(Savings.Line line, String back, String csrf) {
        long account = line.account().id();
        Html rows = line.positions().stream().map(position -> Html.of("""
                <tr><td>%s <code>%s</code></td><td class="num">%s</td><td class="num" title="%s">%s</td><td class="num">%s</td><td class="num %s">%s</td>\
                <td><form method="post" action="/epargne/ligne/%s/supprimer" class="inline-form">%s<button class="link-button" type="submit" aria-label="Supprimer %s">×</button></form></td></tr>""",
                position.position().label(), position.position().symbol(), Format.number(position.position().quantity(), 4), position.priceSource(), Format.euro(position.price()),
                euroWhole(position.value()), position.gain().signum() >= 0 ? "positive" : "negative", Format.signedEuroWhole(position.gain()),
                position.position().id(), token(csrf, back), position.position().label())).collect(Html.joining());
        Html table = line.positions().isEmpty() ? Html.EMPTY : Html.of("""
                <table class="data positions-table"><thead><tr><th>Titre</th><th class="num">Quantité</th><th class="num">Cours</th><th class="num">Valeur</th>\
                <th class="num">Plus-value</th><th></th></tr></thead><tbody>%s</tbody></table>""", rows);
        Html paste = Html.of("""
                <details><summary>Importer l'export du courtier</summary>%s
                <form method="post" action="/epargne/compte/%s/import" class="stack" enctype="multipart/form-data">%s%s<label>Réponse de l'IA
                <textarea name="texte" rows="10" placeholder="IE00B4L5Y983;ETF MSCI World;12,4;1050,30"></textarea></label>
                <div class="form-actions"><button type="submit">Voir l'aperçu</button></div></form></details>""",
                Components.llmPrompt("prompt-bourse-" + account, PositionCsv.llmPrompt()), account, token(csrf, back), Components.localAiInput("bourse"));
        Html add = Html.of("""
                <details><summary>Ajouter une ligne</summary><form method="post" action="/epargne/compte/%s/ligne" class="stack">%s%s
                <div class="form-actions"><button type="submit">Ajouter</button></div></form></details>""",
                account, token(csrf, back), Components.fields(3,
                        Components.input(Input.of("Code (ex. AI.PA)", "symbol", Field.TEXT).required().maxLength(20).placeholder("AI.PA"), Map.of()),
                        Components.input(Input.of("Nom", "label", Field.TEXT).maxLength(60), Map.of()),
                        Components.input(Input.of("Quantité", "quantity", Field.NUMBER).required(), Map.of()),
                        Components.input(Input.of("Prix de revient total", "cost", Field.MONEY).required(), Map.of()),
                        Components.input(Input.of("Cours (vide : du jour)", "price", Field.MONEY), Map.of())));
        return Html.of("<tr class=\"positions\"><td colspan=\"5\">%s<div class=\"position-tools\">%s%s</div></td></tr>", table, paste, add);
    }

    /** Menu « Gerer » d'un compte : nouveau solde, versement programme, suppression. */
    private static Html manage(Savings.Line line, String back, String csrf) {
        SavingsRepository.Account account = line.account();
        SavingsRepository.Contribution current = account.contribution();
        List<Components.Option> frequencies = Arrays.stream(fr.patria.common.Frequency.values()).filter(frequency -> frequency != fr.patria.common.Frequency.ONCE)
                .map(frequency -> new Components.Option(frequency.name(), frequency.label())).toList();
        return Html.of("""
                <details class="row-menu"><summary>Gérer</summary><div class="row-menu-body">
                <form method="post" action="/epargne/compte/%s" class="row-form">%s<label>Solde du jour<input name="balance" inputmode="decimal" value="%s"></label>\
                <button class="secondary" type="submit">Mettre à jour</button></form>
                <form method="post" action="/epargne/compte/%s/versement" class="row-form">%s<label>Versement programmé<input name="montant" inputmode="decimal" value="%s"></label>%s\
                <button class="secondary" type="submit">Enregistrer</button></form>%s
                <form method="post" action="/epargne/compte/%s/supprimer" data-confirm="Supprimer « %s » et ses lignes ?">%s<button class="danger" type="submit">Supprimer le compte</button></form>
                </div></details>""",
                account.id(), token(csrf, back), line.balance().toPlainString(),
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
                <p class="muted small">Mois sans relevé : dernier solde connu.</p>
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
