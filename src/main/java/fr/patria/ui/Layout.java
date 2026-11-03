package fr.patria.ui;

/** Squelette commun des pages : en-tete, navigation principale, contenu. */
public final class Layout {
    /** Entree active de la navigation principale. */
    public enum Section { HOME, BUDGET, REAL_ESTATE, ADMINISTRATION, NONE }

    /** Rubriques de l'administration, affichees en navigation laterale. */
    public enum AdminSection {
        HOUSEHOLD("/administration/foyer", "Foyer et revenus"),
        EXPENSES("/administration/depenses", "Dépenses"),
        PROPERTIES("/administration/biens", "Biens et prêts"),
        SHARES("/administration/repartition", "Répartition");

        private final String href;
        private final String label;

        AdminSection(String href, String label) {
            this.href = href;
            this.label = label;
        }

        public String href() {
            return href;
        }
    }

    private static final Html LOGOUT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/></svg>");

    private Layout() {
    }

    /** Page d'une personne connectee ({@code csrf} non nul) ou anonyme (sans en-tete). */
    public static Html page(String title, Section section, String csrf, Html content) {
        return page(title, section, csrf, "", content);
    }

    /**
     * Comme {@link #page(String, Section, String, Html)}, {@code scope} (par exemple {@code ?personne=2}) etant ajoute
     * aux liens Accueil et Budget pour rester sur la personne choisie.
     */
    public static Html page(String title, Section section, String csrf, String scope, Html content) {
        Html header = csrf == null ? Html.EMPTY : Html.of("""
                <header class="topbar"><div class="shell navigation">%s<nav>%s%s</nav>
                <form method="post" action="/deconnexion">%s<button class="quiet logout" type="submit" aria-label="Déconnexion" title="Déconnexion">%s<span>Déconnexion</span></button></form>
                </div></header>""",
                brand(), Html.concat(navLink("/" + scope, "Accueil", section == Section.HOME), navLink("/budget" + scope, "Budget", section == Section.BUDGET),
                        navLink("/immobilier", "Immobilier", section == Section.REAL_ESTATE)),
                section == Section.ADMINISTRATION
                        ? Html.raw("<a href=\"/administration\" class=\"active\" aria-current=\"page\" aria-label=\"Administration\">Admin<span class=\"long\">istration</span></a>")
                        : Html.raw("<a href=\"/administration\" aria-label=\"Administration\">Admin<span class=\"long\">istration</span></a>"),
                Components.csrfField(csrf), LOGOUT_ICON);
        return document(title, "", Html.concat(header, Html.of("<main class=\"shell\">%s</main>", content)));
    }

    /** Page d'une rubrique d'administration (foyer, depenses, biens) avec sa navigation laterale. */
    public static Html adminSection(String title, String csrf, AdminSection active, Html content) {
        Html links = java.util.Arrays.stream(AdminSection.values()).map(section -> navLink(section.href, section.label, section == active)).collect(Html.joining());
        return adminPage(title, csrf, Html.of("<aside class=\"admin-nav\"><p class=\"eyebrow\">Administration</p>%s</aside>", links), content);
    }

    /** Page d'administration d'un bien : navigation laterale + contenu. */
    public static Html adminPage(String title, String csrf, Html sideNav, Html content) {
        return page(title, Section.ADMINISTRATION, csrf, Html.of("<div class=\"admin-layout\">%s<div>%s</div></div>", sideNav, content));
    }

    public static Html message(String title, String text, String csrf) {
        return page(title + " · Patria", Section.NONE, csrf, Html.of("""
                <section class="card empty-state"><h1>%s</h1><p class="muted">%s</p>
                <a class="button secondary" href="/">Retour à la vue d'ensemble</a></section>""", title, text));
    }

    public static Html document(String title, String bodyClass, Html body) {
        return Html.of("""
                <!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title><link rel="icon" type="image/png" href="%s"><link rel="stylesheet" href="%s"><script defer src="%s"></script></head>
                <body class="%s">%s</body></html>""",
                title, Assets.url("/images/patria-logo.png"), Assets.url("/css/patria.css"), Assets.url("/js/app.js"), bodyClass, body);
    }

    public static Html brand() {
        return Html.of("<a class=\"brand\" href=\"/\"><img class=\"brand-mark\" src=\"%s\" width=\"30\" height=\"30\" alt=\"\"><span>Patria</span></a>",
                Assets.url("/images/patria-logo.png"));
    }

    public static Html navLink(String href, String label, boolean active) {
        return active ? Html.of("<a href=\"%s\" class=\"active\" aria-current=\"page\">%s</a>", href, label)
                : Html.of("<a href=\"%s\">%s</a>", href, label);
    }
}
