package fr.patria.savings;

import static fr.patria.ui.Format.euroWhole;

import fr.patria.household.Person;
import fr.patria.ui.Components;
import fr.patria.ui.Format;
import fr.patria.ui.Html;
import fr.patria.ui.Layout;
import fr.patria.ui.Notice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Page Bourse : deux entrees (importer un releve, ajouter un titre), un bloc par compte avec ses titres, et les
 * ecrans dedies a l'ajout et a l'import. Aucun formulaire dans les tableaux : chaque titre se gere par son menu.
 */
final class StocksPages {
    /** Ou trouver l'export de chaque courtier (verifie en novembre 2026 ; les menus changent). */
    static final List<String[]> BROKERS = List.of(
            new String[]{"Trade Republic", "Appli : Profil → Relevés de compte → Export des transactions → Toutes les transactions (CSV)."},
            new String[]{"Degiro", "Site : Activité → Transactions, date de début = premier achat → Exporter (CSV). Ou Portefeuille → Exporter."},
            new String[]{"Revolut", "Appli : Investir → … → Documents → Relevé de compte, format CSV, toute la période."},
            new String[]{"BoursoBank", "Espace client → Bourse → le PEA ou compte-titres → opérations → exporter ; à défaut le relevé de portefeuille en PDF (passer par l'IA)."},
            new String[]{"Fortuneo", "Espace client → Bourse → relevé de portefeuille en PDF (passer par l'IA)."},
            new String[]{"Coinbase", "Profil → Relevés → Générer un relevé des transactions (CSV)."},
            new String[]{"Binance", "Portefeuille → Historique des transactions → Exporter (CSV)."},
            new String[]{"Autre courtier", "Un CSV avec une colonne ISIN, code ou symbole et une colonne quantité est lu directement ; un PDF passe par l'IA."});

    /** Le compte de destination choisi : un compte existant, ou un nouveau (type, nom, titulaire). */
    record Target(String account, String type, String name, String holder) {
        static final Target NONE = new Target("", "SECURITIES", "", "");
    }

    private StocksPages() {
    }

    // ---- Page principale ----

    static Html page(Savings.Overview portfolio, Notice notice, String csrf) {
        Html actions = Html.concat(Components.button("/bourse/ajouter", "Ajouter un titre"), Components.secondaryButton("/bourse/importer", "Importer un relevé"));
        Html heading = Components.heading("Vos placements en bourse", "Bourse", "PEA, comptes-titres et cryptos, au cours du jour.", portfolio.isEmpty() ? Html.EMPTY : actions);
        if (portfolio.isEmpty()) {
            return Layout.page("Bourse · Patria", Layout.Section.STOCKS, csrf, Html.concat(heading, Components.notice(notice), Html.raw("""
                    <section class="stock-start"><a class="card stock-choice" href="/bourse/importer"><b>Importer un relevé</b><span>Déposez l'export de votre courtier
                    (Trade Republic, Degiro, Revolut, BoursoBank…) : le compte et ses titres sont créés d'un coup, au cours du jour.</span><span class="button">Importer</span></a>
                    <a class="card stock-choice" href="/bourse/ajouter"><b>Ajouter un titre</b><span>Cherchez un ETF, une action ou une crypto par son nom ou son ISIN,
                    puis indiquez la quantité et le prix moyen d'achat.</span><span class="button secondary">Ajouter</span></a></section>""")));
        }
        BigDecimal cost = portfolio.positions().stream().map(line -> line.position().cost()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gain = portfolio.positions().stream().map(Savings.PositionLine::gain).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cash = portfolio.lines().stream().map(Savings.Line::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        Html tiles = Components.metrics(4,
                tile("Valeur", "Titres au cours du jour, plus les espèces des comptes.", euroWhole(portfolio.total()), "accent",
                        Format.plural(portfolio.lines().size(), "compte", "comptes")),
                tile("Plus-value latente", "Valeur des titres − ce qu'ils ont coûté. Rien n'est gagné ou perdu avant la vente.", Format.signedEuroWhole(gain), tone(gain),
                        cost.signum() == 0 ? null : Format.signedPercent(percent(gain, cost)) + " depuis l'achat"),
                tile("Investi", "Ce que les titres détenus ont coûté (prix de revient).", euroWhole(cost), "", Format.plural(portfolio.positions().size(), "titre", "titres")),
                tile("Espèces", "Argent déposé chez les courtiers, pas encore investi.", euroWhole(cash), "", null));
        Html chart = portfolio.positions().isEmpty() ? Html.EMPTY : Components.card("Évolution du portefeuille", null, Html.EMPTY, Html.raw(
                "<div data-fragment-src=\"/bourse/graphique\" data-fragment-auto><p class=\"muted\">Chargement des cours…</p></div>"
                        + "<p class=\"muted small\">Titres actuels valorisés aux cours passés : la courbe ignore les dates d'achat.</p>"));
        Html accounts = portfolio.lines().stream().map(line -> account(line, csrf)).collect(Html.joining());
        return Layout.page("Bourse · Patria", Layout.Section.STOCKS, csrf, Html.concat(heading, Components.notice(notice), tiles, chart, accounts,
                Html.raw("<p class=\"muted small\">Cours Yahoo Finance, en différé. Valeur indicative : un placement en bourse peut perdre de la valeur. "
                        + "Pas un conseil en investissement.</p>")));
    }

    /** Un compte : en-tete (type, titulaire, espees, menu), puis ses titres. */
    private static Html account(Savings.Line line, String csrf) {
        SavingsRepository.Account account = line.account();
        BigDecimal gain = line.positions().stream().map(Savings.PositionLine::gain).reduce(BigDecimal.ZERO, BigDecimal::add);
        Html head = Html.of("<span class=\"stock-account-meta\">%s · %s · espèces %s · <b>%s</b></span>%s", account.kind().label(), line.owner(),
                euroWhole(line.balance()), euroWhole(line.value()), SavingsPages.manage(line, null, csrf));
        Html body;
        if (line.positions().isEmpty()) {
            body = Html.of("<p class=\"empty\">Aucun titre sur ce compte. <a href=\"/bourse/ajouter?compte=%s\">Ajouter un titre</a> · "
                    + "<a href=\"/bourse/importer?compte=%s\">Importer un relevé</a></p>", account.id(), account.id());
        } else {
            Html rows = line.positions().stream().map(position -> row(position, csrf)).collect(Html.joining());
            body = Html.of("""
                    <div class="table-wrap"><table class="data positions-table"><thead><tr><th>Titre</th><th class="num">Quantité</th><th class="num">Prix moyen</th>\
                    <th class="num">Cours</th><th class="num">Valeur</th><th class="num">Plus-value</th><th></th></tr></thead><tbody>%s</tbody>\
                    <tfoot><tr><th colspan="4">Total des titres</th><th class="num">%s</th><th class="num %s">%s</th><th></th></tr></tfoot></table></div>""",
                    rows, euroWhole(line.value().subtract(line.balance())), tone(gain), Format.signedEuroWhole(gain));
        }
        return Html.of("<div id=\"compte-%s\">%s</div>", account.id(), Components.card(account.name(), null, head, body));
    }

    /** Un titre : chiffres, menu Gerer (quantite, prix moyen, suppression), courbe depliable sous la ligne. */
    private static Html row(Savings.PositionLine line, String csrf) {
        SavingsRepository.Position position = line.position();
        BigDecimal average = position.quantity().signum() == 0 ? BigDecimal.ZERO : position.cost().divide(position.quantity(), 2, RoundingMode.HALF_UP);
        BigDecimal percent = position.cost().signum() == 0 ? BigDecimal.ZERO : percent(line.gain(), position.cost());
        Html menu = Html.of("""
                <details class="row-menu"><summary>Gérer</summary><div class="row-menu-body">
                <form method="post" action="/bourse/ligne/%s" class="row-form">%s<label>Quantité<input name="quantity" inputmode="decimal" value="%s" required></label>\
                <label>Prix moyen par part (€)<input name="pru" inputmode="decimal" value="%s" required></label><button class="secondary" type="submit">Enregistrer</button></form>
                <form method="post" action="/bourse/ligne/%s/supprimer" data-confirm="Supprimer « %s » de ce compte ?">%s<button class="danger" type="submit">Supprimer</button></form>
                </div></details>""", position.id(), Components.csrfField(csrf), position.quantity().stripTrailingZeros().toPlainString(), average.toPlainString(),
                position.id(), position.label(), Components.csrfField(csrf));
        return Html.of("""
                <tr><td><b>%s</b> <code>%s</code></td><td class="num">%s</td><td class="num">%s</td><td class="num" title="%s">%s</td><td class="num">%s</td>\
                <td class="num %s">%s<small>%s</small></td><td class="actions">%s</td></tr>
                <tr class="position-chart"><td colspan="7"><details data-fragment-src="/bourse/ligne/%s/graphique"><summary>Courbe du cours</summary>\
                <div data-fragment-target><p class="muted">Chargement…</p></div></details></td></tr>""",
                position.label(), position.symbol(), Format.number(position.quantity(), 4), Format.euro(average), line.priceSource(), Format.euro(line.price()),
                euroWhole(line.value()), tone(line.gain()), Format.signedEuroWhole(line.gain()), Format.signedPercent(percent), menu, position.id());
    }

    // ---- Ajouter un titre ----

    /**
     * Ecran d'ajout : recherche (nom, ISIN, code), choix d'un resultat, puis quantite, prix et compte. {@code symbol}
     * non nul : titre choisi, le formulaire final s'affiche.
     */
    static Html add(List<Savings.Line> accounts, List<Person> people, String query, List<MarketFeeds.Found> results, boolean offline, String symbol, String name,
                    String preselect, Notice notice, String csrf) {
        Html search = Html.of("""
                <form method="get" action="/bourse/ajouter" class="title-search" role="search">%s<label>Nom, ISIN ou code<input type="search" name="q" value="%s" maxlength="60"
                required autofocus placeholder="MSCI World, FR0010315770, Apple, BTC"></label><button class="secondary" type="submit">Chercher</button></form>""",
                preselect == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"compte\" value=\"%s\">", preselect), query == null ? "" : query);
        Html found = Html.EMPTY;
        if (query != null && symbol == null) {
            found = results.isEmpty() ? Html.of("<p class=\"muted\">%s</p>", offline ? "Recherche indisponible en mode hors ligne : saisissez le code ci-dessous."
                    : "Aucun titre trouvé pour « " + query + " ». Essayez l'ISIN, ou saisissez le code ci-dessous.")
                    : Html.of("<ul class=\"title-results\">%s</ul>", results.stream().map(result -> Html.of("""
                    <li><div><b>%s</b><small><code>%s</code> · %s%s</small></div><a class="button secondary" href="/bourse/ajouter?symbole=%s&amp;nom=%s%s">Choisir</a></li>""",
                    result.name(), result.symbol(), result.type().isEmpty() ? "titre" : result.type(), result.exchange().isEmpty() ? "" : " · " + result.exchange(),
                    encode(result.symbol()), encode(result.name().length() > 60 ? result.name().substring(0, 60) : result.name()),
                    preselect == null ? "" : "&compte=" + preselect)).collect(Html.joining()));
        }
        Html manual = Html.of("""
                <details%s><summary>Le titre n'est pas trouvé ? Saisir son code</summary><form method="get" action="/bourse/ajouter" class="title-search">%s\
                <label>Code Yahoo Finance (AI.PA, CW8.PA, BTC…)<input name="symbole" maxlength="20" required></label><label>Nom<input name="nom" maxlength="60"></label>\
                <button class="secondary" type="submit">Continuer</button></form></details>""", Html.raw(query != null && results.isEmpty() ? " open" : ""),
                preselect == null ? Html.EMPTY : Html.of("<input type=\"hidden\" name=\"compte\" value=\"%s\">", preselect));
        Html content;
        if (symbol == null) {
            content = Components.card("1. Trouver le titre", null, Html.EMPTY, Html.concat(search, found, manual));
        } else {
            content = Components.card("2. " + (name == null || name.isBlank() ? symbol : name), null, Html.raw("<a href=\"/bourse/ajouter\">Changer de titre</a>"), Html.of("""
                    <form method="post" action="/bourse/ajouter" class="stack">%s<input type="hidden" name="symbole" value="%s"><input type="hidden" name="nom" value="%s">
                    <p class="muted">Code <code>%s</code>. Le cours du jour est repris automatiquement.</p>
                    <div class="form-grid cols-3"><label>Quantité<input name="quantity" inputmode="decimal" required></label>
                    <label>Prix d'achat (€)<input name="montant" inputmode="decimal" required></label>
                    <label>Ce prix est<select name="prix"><option value="pru">le prix moyen par part (PRU)</option><option value="total">le total payé</option></select></label></div>
                    %s<div class="form-actions"><button type="submit">Ajouter le titre</button></div></form>""",
                    Components.csrfField(csrf), symbol, name == null ? "" : name, symbol, target(accounts, people, preselect, Target.NONE)));
        }
        return Layout.page("Ajouter un titre · Bourse · Patria", Layout.Section.STOCKS, csrf, Html.concat(Components.backLink("/bourse", "Bourse"),
                Components.heading("Bourse", "Ajouter un titre", "ETF, action ou crypto : le titre rejoint un de vos comptes, ou un nouveau.", Html.EMPTY),
                Components.notice(notice), content));
    }

    // ---- Importer un releve ----

    static Html importForm(List<Savings.Line> accounts, List<Person> people, String preselect, Notice notice, String csrf) {
        Html help = BROKERS.stream().map(broker -> Html.of("<li><b>%s</b> : %s</li>", broker[0], broker[1])).collect(Html.joining());
        Html options = BROKERS.stream().map(broker -> Html.of("<option>%s</option>", broker[0])).collect(Html.joining());
        Html form = Html.of("""
                <form method="post" action="/bourse/importer" class="stack" enctype="multipart/form-data">%s
                <label>Courtier<select name="courtier">%s</select></label>
                <details><summary>Comment obtenir l'export ?</summary><ul class="plain-list">%s</ul></details>
                <label>Fichier exporté (CSV)<input name="csv" type="file" accept=".csv,.txt,text/csv,text/plain"></label>
                %s
                <details><summary>Vous n'avez qu'un PDF ? Passer par une IA</summary>%s%s<label>Réponse de l'IA
                <textarea name="texte" rows="6" placeholder="IE00B4L5Y983;ETF MSCI World;12,4;1050,30"></textarea></label></details>
                <div class="form-actions"><button type="submit">Voir l'aperçu</button></div></form>""",
                Components.csrfField(csrf), options, help, target(accounts, people, preselect, Target.NONE),
                Components.llmPrompt("prompt-bourse", PositionCsv.llmPrompt()), Components.localAiInput("bourse"));
        return Layout.page("Importer un relevé · Bourse · Patria", Layout.Section.STOCKS, csrf, Html.concat(Components.backLink("/bourse", "Bourse"),
                Components.heading("Bourse", "Importer un relevé", "Le fichier de votre courtier, tel quel : Patria reconnaît les colonnes, cumule achats et ventes, "
                        + "puis vous montre l'aperçu avant d'enregistrer.", Html.EMPTY),
                Components.notice(notice), Components.card("Relevé", null, Html.EMPTY, form)));
    }

    /** Apercu : une case par titre ; le compte de destination voyage avec le contenu jusqu'a la validation. */
    static Html preview(String accountName, List<PositionCsv.Row> rows, String content, Target target, BigDecimal cash, String csrf) {
        long fresh = rows.stream().filter(PositionCsv.Row::fresh).count();
        Html body = rows.stream().map(row -> row.valid() ? Html.of("<tr><td>%s</td><td>%s</td><td><code>%s</code>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td>%s</td></tr>",
                row.fresh() ? Html.of("<input type=\"checkbox\" name=\"importer_%s\" value=\"on\" form=\"positions-import\" checked aria-label=\"Importer %s\">", row.line(), row.label())
                        : Html.text(String.valueOf(row.line())),
                row.label(), row.symbol(), row.symbol().equals(row.code()) ? Html.EMPTY : Html.of("<small>%s</small>", row.code()),
                Format.number(row.quantity(), 6), euroWhole(row.cost()),
                row.duplicate() ? Html.raw("<span class=\"badge\">En double, ignorée</span>")
                        : row.existing() != null ? Html.raw("<span class=\"badge paid\">Mise à jour</span>") : PositionCsv.isIsin(row.symbol())
                        ? Html.raw("<span class=\"badge warn\" title=\"Code de cotation introuvable : comptée au prix de revient\">Sans cours</span>")
                        : Html.raw("<span class=\"badge paid\">Nouveau</span>"))
                : Html.of("<tr class=\"row-error\"><td>%s</td><td colspan=\"5\" class=\"negative\">%s</td></tr>", row.line(), row.error())).collect(Html.joining());
        String encoded = java.util.Base64.getUrlEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean withoutQuote = rows.stream().anyMatch(row -> row.valid() && PositionCsv.isIsin(row.symbol()));
        Html actions = fresh == 0 ? Html.raw("<p class=\"muted\">Rien de nouveau à importer.</p>") : Html.of("""
                <form id="positions-import" method="post" action="/bourse/importer/valider" class="form-actions">%s<input type="hidden" name="contenu" value="%s">\
                <input type="hidden" name="compte" value="%s"><input type="hidden" name="compte_type" value="%s"><input type="hidden" name="compte_nom" value="%s">\
                <input type="hidden" name="titulaire" value="%s">%s<a class="button secondary" href="/bourse/importer">Annuler</a><button type="submit">Importer la sélection</button></form>""",
                Components.csrfField(csrf), encoded, target.account(), target.type(), target.name(), target.holder(), cash == null ? Html.EMPTY : Html.of("""
                        <input type="hidden" name="especes" value="%s"><label class="check"><input type="checkbox" name="maj_especes" value="on" checked> Espèces du compte : \
                        %s d'après le relevé (versements − achats + ventes, dividendes, frais et impôts)</label>""", cash.toPlainString(), Format.euro(cash)));
        return Layout.page("Aperçu de l'import · Bourse · Patria", Layout.Section.STOCKS, csrf, Html.concat(Components.backLink("/bourse/importer", "Importer un relevé"),
                Components.heading("Bourse", "Aperçu de l'import", Format.plural(fresh, "titre", "titres") + " vers « " + accountName + " ». Les lignes en erreur sont ignorées.",
                        Html.EMPTY),
                Components.card("Titres", rows.size(), Html.EMPTY, Html.concat(Components.table(List.of("Importer", "Titre", "Code", "#Quantité", "#Prix de revient", "État"),
                        body, false, false), withoutQuote ? Html.raw("<p class=\"muted small\">« Sans cours » : code de cotation introuvable (mode hors ligne ou titre "
                        + "inconnu) ; le titre est compté à son prix de revient.</p>") : Html.EMPTY, actions))));
    }

    /**
     * Choix du compte : un compte existant, ou un nouveau (type, nom, titulaire). Sans compte existant, seuls les champs
     * du nouveau compte s'affichent.
     */
    private static Html target(List<Savings.Line> accounts, List<Person> people, String preselect, Target current) {
        Html holders = people.stream().map(person -> Html.of("<option value=\"%s\"%s>%s</option>", person.id(),
                Html.raw(String.valueOf(person.id()).equals(current.holder()) ? " selected" : ""), person.name())).collect(Html.joining());
        Html fresh = Html.of("""
                <div class="form-grid cols-3"><label>Type<select name="compte_type"><option value="SECURITIES">Compte-titres</option><option value="PEA">PEA</option></select></label>\
                <label>Nom du compte<input name="compte_nom" maxlength="60" placeholder="Trade Republic"></label><label>Titulaire<select name="titulaire">%s</select></label></div>""", holders);
        if (accounts.isEmpty()) {
            return Html.of("<fieldset class=\"target\"><legend>Nouveau compte</legend><input type=\"hidden\" name=\"compte\" value=\"nouveau\">%s"
                    + "<p class=\"muted small\">Les espèces non investies se saisissent ensuite, dans le menu Gérer du compte (0 par défaut).</p></fieldset>", fresh);
        }
        Html options = accounts.stream().map(line -> Html.of("<option value=\"%s\"%s>%s (%s · %s)</option>", line.account().id(),
                Html.raw(String.valueOf(line.account().id()).equals(preselect) ? " selected" : ""), line.account().name(), line.account().kind().label(), line.owner()))
                .collect(Html.joining());
        return Html.of("""
                <fieldset class="target"><legend>Compte</legend><label>Sur le compte<select name="compte">%s<option value="nouveau">Nouveau compte…</option></select></label>
                <details><summary>Nouveau compte : type, nom, titulaire</summary>%s</details></fieldset>""", options, fresh);
    }

    private static Html tile(String label, String hint, String value, String tone, String sub) {
        return Html.of("<article class=\"metric\">%s<strong class=\"%s\">%s</strong>%s</article>", Components.label(label, hint), tone, value,
                sub == null ? Html.EMPTY : Html.of("<small>%s</small>", sub));
    }

    static String tone(BigDecimal value) {
        return value.signum() > 0 ? "positive" : value.signum() < 0 ? "negative" : "";
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
