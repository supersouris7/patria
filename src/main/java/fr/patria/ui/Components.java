package fr.patria.ui;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Briques d'interface reutilisees par toutes les pages. */
public final class Components {
    private Components() {
    }

    // ---- Structure de page ----

    /** Titre de page : surtitre, titre, sous-titre facultatif et boutons d'action. */
    public static Html heading(String eyebrow, String title, Object subtitle, Html actions) {
        Html sub = subtitle == null || subtitle.toString().isEmpty() ? Html.EMPTY : Html.of("<p class=\"muted\">%s</p>", subtitle);
        Html buttons = actions.isEmpty() ? Html.EMPTY : Html.of("<div class=\"heading-actions\">%s</div>", actions);
        return Html.of("<section class=\"page-heading\"><div><p class=\"eyebrow\">%s</p><h1>%s</h1>%s</div>%s</section>", eyebrow, title, sub, buttons);
    }

    public static Html backLink(String href, String label) {
        return Html.of("<p><a class=\"back-link\" href=\"%s\">← %s</a></p>", href, label);
    }

    /** Carte titree, avec compteur facultatif et boutons a droite du titre. */
    public static Html card(String title, Integer count, Html actions, Html body) {
        Html badge = count == null ? Html.EMPTY : Html.of(" <span class=\"count\">%s</span>", count);
        Html buttons = actions.isEmpty() ? Html.EMPTY : Html.of("<div class=\"heading-actions\">%s</div>", actions);
        return Html.of("<section class=\"card section\"><div class=\"section-title\"><h2>%s%s</h2>%s</div>%s</section>", title, badge, buttons, body);
    }

    public static Html emptyState(String title, String text, Html action) {
        return Html.of("<section class=\"card empty-state\"><h2>%s</h2><p class=\"muted\">%s</p>%s</section>", title, text, action);
    }

    public static Html notice(Notice notice) {
        if (notice == null) return Html.EMPTY;
        return Html.of("<p class=\"notice %s\" role=\"%s\">%s</p>", notice.error() ? "error" : "success", notice.error() ? "alert" : "status", notice.text());
    }

    public static Html button(String href, String label) {
        return Html.of("<a class=\"button\" href=\"%s\">%s</a>", href, label);
    }

    /** Bouton de telechargement de la synthese PDF d'une page de consultation. */
    public static Html pdfButton(String href) {
        return Html.of("<a class=\"button secondary\" href=\"%s\" download title=\"Synthèse au format PDF\">Exporter en PDF</a>", href);
    }

    public static Html secondaryButton(String href, String label) {
        return Html.of("<a class=\"button secondary\" href=\"%s\">%s</a>", href, label);
    }

    // ---- Indicateurs ----

    /** Tuile chiffree : libelle (avec info-bulle facultative), valeur, ligne secondaire facultative. */
    public static Html tile(String label, String hint, Object value, Object sub) {
        Html subLine = sub == null || sub.toString().isEmpty() ? Html.EMPTY : Html.of("<small>%s</small>", sub);
        return Html.of("<article class=\"metric\">%s<strong>%s</strong>%s</article>", label(label, hint), value, subLine);
    }

    public static Html label(String label, String hint) {
        return hint == null ? Html.of("<span class=\"metric-label\">%s</span>", label)
                : Html.of("<span class=\"metric-label\" title=\"%s\">%s</span>", hint, label);
    }

    /** Rangee de tuiles sur 3 ou 4 colonnes. */
    public static Html metrics(int columns, Html... tiles) {
        return Html.of("<section class=\"metrics cols-%s\">%s</section>", columns, Html.concat(tiles));
    }

    /** Rangee de chiffres compacte, sans cadres (a l'interieur d'une carte). */
    public static Html strip(Html... items) {
        return Html.of("<section class=\"metrics cols-%s strip\">%s</section>", items.length, Html.concat(items));
    }

    /**
     * Ligne d'un detail de calcul. Le niveau fixe la taille du chiffre : point de depart, detail (ajout ou retrait),
     * total (resultat, le plus grand) ou simple repere.
     *
     * @param aside precision affichee en petit a cote du libelle (par exemple « 1 800 €/mois »), ou {@code null}
     * @param inTile chiffre deja affiche dans une tuile : garde sa place dans le calcul, sans mise en valeur
     */
    public record Line(String label, String hint, Html value, Level level, String aside, boolean inTile) {
        public enum Level { LEAD, DETAIL, TOTAL, NOTE }

        public static Line lead(String label, String hint, Html value) {
            return new Line(label, hint, value, Level.LEAD, null, false);
        }

        public static Line of(String label, String hint, Html value) {
            return new Line(label, hint, value, Level.DETAIL, null, false);
        }

        public static Line total(String label, String hint, Html value) {
            return new Line(label, hint, value, Level.TOTAL, null, false);
        }

        public static Line note(String label, String hint, Html value) {
            return new Line(label, hint, value, Level.NOTE, null, false);
        }

        public Line aside(String text) {
            return new Line(label, hint, value, level, text, inTile);
        }

        /** Chiffre deja mis en avant dans une tuile de la meme page. */
        public Line echo() {
            return new Line(label, hint, value, level, aside, true);
        }
    }

    /** Detail d'un calcul, ligne a ligne, comme un releve : « Valeur − frais − dette = valeur nette ». */
    public static Html breakdown(String title, List<Line> lines) {
        Html rows = lines.stream().map(line -> Html.of("<div class=\"line %s%s\"><dt><span%s>%s</span>%s</dt><dd>%s</dd></div>",
                line.level().name().toLowerCase(), line.inTile() ? " echo" : "", line.hint() == null ? Html.EMPTY : Html.of(" class=\"hint\" title=\"%s\"", line.hint()), line.label(),
                line.aside() == null ? Html.EMPTY : Html.of(" <small class=\"aside\">%s</small>", line.aside()), line.value())).collect(Html.joining());
        return Html.of("<div class=\"breakdown\"><h3>%s</h3><dl>%s</dl></div>", title, rows);
    }

    public static Html progress(int percent) {
        return Html.of("""
                <div class="progress-cell"><div class="progress" role="progressbar" aria-valuenow="%s" aria-valuemin="0" aria-valuemax="100">\
                <span style="width:%s%%"></span></div><small>%s %%</small></div>""".replace("%%", "%"), percent, percent, percent);
    }

    public static Html paidBadge(boolean paid) {
        return paid ? Html.raw("<span class=\"badge paid\">Payée</span>") : Html.raw("<span class=\"badge\">À venir</span>");
    }

    // ---- Tableaux ----

    /** Tableau de donnees. Une colonne dont le libelle commence par « # » est numerique (alignee a droite). */
    public static Html table(List<String> columns, Html rows, boolean sortable, boolean filterable) {
        return table(columns, rows, Html.EMPTY, sortable, filterable);
    }

    /** Tableau avec une ligne de pied (totaux), hors tri et hors filtre. */
    public static Html table(List<String> columns, Html rows, Html footer, boolean sortable, boolean filterable) {
        Html head = columns.stream().map(column -> column.startsWith("#")
                ? Html.of("<th class=\"num\">%s</th>", column.substring(1)) : Html.of("<th>%s</th>", column)).collect(Html.joining());
        String classes = sortable ? "data sortable" : "data";
        return Html.of("<div class=\"table-wrap\"><table class=\"%s\"%s><thead><tr>%s</tr></thead><tbody>%s</tbody>%s</table></div>",
                classes, Html.raw(filterable ? " data-filter" : ""), head, rows, footer.isEmpty() ? Html.EMPTY : Html.of("<tfoot>%s</tfoot>", footer));
    }

    /** Cellule numerique ; {@code sort} est la valeur brute utilisee pour le tri. */
    public static Html number(BigDecimal sort, Object display) {
        return sort == null ? Html.of("<td class=\"num\">%s</td>", display)
                : Html.of("<td class=\"num\" data-sort=\"%s\">%s</td>", Format.plain(sort), display);
    }

    public static Html cell(Object content) {
        return Html.of("<td>%s</td>", content);
    }

    public static Html sortedCell(Object sort, Object content) {
        return Html.of("<td data-sort=\"%s\">%s</td>", sort, content);
    }

    public static Html link(String href, Object label) {
        return Html.of("<a href=\"%s\">%s</a>", href, label);
    }

    // ---- Formulaires ----

    public static Html csrfField(String csrf) {
        return Html.of("<input type=\"hidden\" name=\"csrf\" value=\"%s\">", csrf);
    }

    /** Type de champ ; {@code MONEY} affiche les montants « 250 000,00 » et les renvoie au format numerique. */
    public enum Field { TEXT, MONEY, NUMBER, DATE }

    public record Input(String label, String name, Field field, boolean mandatory, int maxLength, String placeholder, String help) {
        public static Input of(String label, String name, Field field) {
            return new Input(label, name, field, false, 0, null, null);
        }

        public Input required() {
            return new Input(label, name, field, true, maxLength, placeholder, help);
        }

        public Input maxLength(int length) {
            return new Input(label, name, field, mandatory, length, placeholder, help);
        }

        public Input placeholder(String text) {
            return new Input(label, name, field, mandatory, maxLength, text, help);
        }

        public Input help(String text) {
            return new Input(label, name, field, mandatory, maxLength, placeholder, text);
        }
    }

    public static Html input(Input input, Map<String, String> values) {
        StringBuilder attributes = new StringBuilder();
        switch (input.field()) {
            case DATE -> attributes.append(" type=\"date\"");
            case MONEY -> attributes.append(" data-money inputmode=\"decimal\"");
            case NUMBER -> attributes.append(" inputmode=\"decimal\"");
            case TEXT -> { }
        }
        if (input.mandatory()) attributes.append(" required");
        if (input.maxLength() > 0) attributes.append(" maxlength=\"").append(input.maxLength()).append('"');
        Html placeholder = input.placeholder() == null ? Html.EMPTY : Html.of(" placeholder=\"%s\"", input.placeholder());
        Html mark = Html.when(input.mandatory(), Html.raw(" <span class=\"required-mark\">*</span>"));
        Html help = input.help() == null ? Html.EMPTY : Html.of("<span class=\"help\">%s</span>", input.help());
        return Html.of("<label>%s%s<input name=\"%s\" value=\"%s\"%s%s>%s</label>",
                input.label(), mark, input.name(), values.getOrDefault(input.name(), ""), Html.raw(attributes.toString()), placeholder, help);
    }

    /**
     * Bouton « Copier le prompt IA » : un texte pret a coller dans un assistant IA avec ses documents, qui renvoie un
     * fichier au format de l'import. Le prompt reste lisible (et copiable a la main) dans un bloc depliable.
     */
    public static Html llmPrompt(String id, String prompt) {
        return Html.of("""
                <div class="llm-prompt"><div class="llm-prompt-actions"><button type="button" class="secondary" data-copy="%s">Copier le prompt IA</button>\
                <span class="muted copy-status" role="status">À coller dans votre assistant IA (ChatGPT, Claude, Le Chat…), en y joignant vos documents.</span></div>\
                <details><summary>Voir le prompt</summary><textarea id="%s" readonly rows="12" aria-label="Prompt pour assistant IA">%s</textarea></details></div>""",
                id, id, prompt);
    }

    public static Html fileInput(String label, String name) {
        return Html.of("<label>%s <span class=\"required-mark\">*</span><input name=\"%s\" type=\"file\" accept=\".csv,text/csv\" required></label>", label, name);
    }

    public record Option(String value, String label) {
    }

    public static Html select(String label, String name, List<Option> options, String selected) {
        Html choices = options.stream().map(option -> option.value().equals(selected)
                ? Html.of("<option value=\"%s\" selected>%s</option>", option.value(), option.label())
                : Html.of("<option value=\"%s\">%s</option>", option.value(), option.label())).collect(Html.joining());
        return Html.of("<label>%s<select name=\"%s\">%s</select></label>", label, name, choices);
    }

    /** Grille de champs sur 2 ou 3 colonnes. */
    public static Html fields(int columns, Html... inputs) {
        return Html.of("<div class=\"form-grid%s\">%s</div>", columns == 3 ? " cols-3" : "", Html.concat(inputs));
    }

    /**
     * Formulaire d'une ligne, ou sa consultation seule ({@code readOnly}) : memes champs, desactives, sans bouton.
     * Sert aux lignes partagees avec un membre dont la vue n'est pas ouverte a l'utilisateur.
     */
    public static Html formOrView(boolean readOnly, String action, String csrf, Html body, String submitLabel) {
        if (!readOnly) return form(action, csrf, false, body, submitLabel);
        return Html.of("""
                <div class="stack"><p class="notice" role="status">Lecture seule : cette ligne concerne aussi un membre dont la vue ne vous est pas ouverte.
                Seul l'administrateur, ou un utilisateur qui a toutes ses vues, peut la modifier.</p><fieldset class="readonly-view" disabled>%s</fieldset></div>""", body);
    }

    /** Formulaire POST protege par le jeton CSRF. */
    public static Html form(String action, String csrf, boolean multipart, Html body, String submitLabel) {
        return Html.of("<form method=\"post\" action=\"%s\"%s class=\"stack\">%s%s<div class=\"form-actions\"><button type=\"submit\">%s</button></div></form>",
                action, Html.raw(multipart ? " enctype=\"multipart/form-data\"" : ""), csrfField(csrf), body, submitLabel);
    }

    /** Carte de suppression avec confirmation navigateur. */
    public static Html dangerZone(String title, String text, String action, String confirm, String buttonLabel, String csrf) {
        return Html.of("""
                <section class="card section danger-zone"><h2>%s</h2><p class="muted">%s</p>
                <form method="post" action="%s" data-confirm="%s">%s<button class="danger" type="submit">%s</button></form></section>""",
                title, text, action, confirm, csrfField(csrf), buttonLabel);
    }

    /** Navigation laterale (administration d'un bien). */
    public static Html sideNav(String title, List<NavItem> items) {
        Html links = items.stream().map(item -> Layout.navLink(item.href(), item.label(), item.active())).collect(Html.joining());
        return Html.of("<aside class=\"admin-nav\"><p class=\"eyebrow\">%s</p>%s</aside>", title, links);
    }

    public record NavItem(String href, String label, boolean active) {
    }
}
