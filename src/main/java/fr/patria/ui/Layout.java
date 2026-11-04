package fr.patria.ui;

import java.util.List;

/** Squelette commun des pages : en-tete, navigation principale, contenu. */
public final class Layout {
    /** Entree active de la navigation principale. */
    public enum Section { HOME, BUDGET, REAL_ESTATE, ADMINISTRATION, ACCOUNT, NONE }

    /** Foyer propose dans le selecteur de l'en-tete. */
    public record Choice(long id, String name) {
    }

    /**
     * Ce que l'en-tete affiche pour la personne connectee : son nom, ses foyers (selecteur a partir de deux) et les
     * rubriques permises. Pose par le routeur pour la duree d'une requete.
     */
    public record Chrome(String name, boolean admin, boolean realEstate, List<Choice> households, long household) {
    }

    public static final ThreadLocal<Chrome> CHROME = new ThreadLocal<>();

    /** Rubriques de l'administration, affichees en navigation laterale ; {@code userLabel} : nom pour un utilisateur, {@code null} si reservee. */
    public enum AdminSection {
        HOUSEHOLD("/administration/foyer", "Foyer et revenus", "Revenus"),
        EXPENSES("/administration/depenses", "Dépenses", "Dépenses"),
        TRANSFERS("/administration/virements", "Virements", "Virements"),
        PROPERTIES("/administration/biens", "Biens et prêts", null),
        SHARES("/administration/repartition", "Répartition", null),
        HOUSEHOLDS("/administration/foyers", "Foyers", null),
        USERS("/administration/utilisateurs", "Utilisateurs", null),
        ACCOUNT("/compte", "Mon compte", "Mon compte");

        private final String href;
        private final String label;
        private final String userLabel;

        AdminSection(String href, String label, String userLabel) {
            this.href = href;
            this.label = label;
            this.userLabel = userLabel;
        }

        public String href() {
            return href;
        }
    }

    private static final Html LOGOUT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/></svg>");

    private static final Html ACCOUNT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21a8 8 0 0 1 16 0\"/></svg>");

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
        Chrome chrome = CHROME.get();
        boolean realEstate = chrome == null || chrome.realEstate();
        Html header = csrf == null ? Html.EMPTY : Html.of("""
                <header class="topbar"><div class="shell navigation">%s<nav>%s%s</nav><div class="account-bar">%s%s
                <form method="post" action="/deconnexion">%s<button class="quiet logout" type="submit" aria-label="Déconnexion" title="Déconnexion">%s<span>Déconnexion</span></button></form>
                </div></div></header>""",
                brand(), Html.concat(shortNavLink("/" + scope, "Prévisionnel", "Prévision", section == Section.HOME),
                        shortNavLink("/budget" + scope, "Budget moyen", "Moyenne", section == Section.BUDGET),
                        realEstate ? shortNavLink("/immobilier", "Immobilier", "Immo", section == Section.REAL_ESTATE) : Html.EMPTY),
                section == Section.ADMINISTRATION
                        ? Html.raw("<a href=\"/administration\" class=\"active\" aria-current=\"page\" aria-label=\"Administration\">Admin<span class=\"long\">istration</span></a>")
                        : Html.raw("<a href=\"/administration\" aria-label=\"Administration\">Admin<span class=\"long\">istration</span></a>"),
                householdSwitch(chrome, csrf), account(chrome, section == Section.ACCOUNT), Components.csrfField(csrf), LOGOUT_ICON);
        return document(title, "", Html.concat(header, Html.of("<main class=\"shell\">%s</main>", content)));
    }

    /** Selecteur du foyer affiche, a partir de deux foyers ; app.js l'envoie des qu'on change de foyer. */
    private static Html householdSwitch(Chrome chrome, String csrf) {
        if (chrome == null || chrome.households().size() < 2) return Html.EMPTY;
        Html options = chrome.households().stream().map(choice -> Html.of("<option value=\"%s\"%s>%s</option>", choice.id(),
                choice.id() == chrome.household() ? Html.raw(" selected") : Html.EMPTY, choice.name())).collect(Html.joining());
        return Html.of("""
                <form method="post" action="/foyer/choisir" class="household-switch">%s<label><span class="visually-hidden">Foyer affiché</span>\
                <select name="foyer" data-autosubmit>%s</select></label><button class="quiet" type="submit">Afficher</button></form>""",
                Components.csrfField(csrf), options);
    }

    /** Lien vers « Mon compte » (mot de passe), avec le nom de la personne connectee. */
    private static Html account(Chrome chrome, boolean active) {
        if (chrome == null) return Html.EMPTY;
        return Html.of("<a class=\"account-link%s\" href=\"/compte\"%s title=\"Mon compte\" aria-label=\"Mon compte (%s)\">%s<span>%s</span></a>",
                active ? " active" : "", Html.raw(active ? " aria-current=\"page\"" : ""), chrome.name(), ACCOUNT_ICON, chrome.name());
    }

    /** Page d'une rubrique d'administration (foyer, depenses, biens) avec sa navigation laterale. */
    public static Html adminSection(String title, String csrf, AdminSection active, Html content) {
        Chrome chrome = CHROME.get();
        boolean admin = chrome == null || chrome.admin();
        Html links = java.util.Arrays.stream(AdminSection.values()).filter(section -> admin || section.userLabel != null)
                .map(section -> navLink(section.href, admin ? section.label : section.userLabel, section == active)).collect(Html.joining());
        return adminPage(title, csrf, Html.of("<aside class=\"admin-nav\"><p class=\"eyebrow\">Administration</p>%s</aside>", links), content);
    }

    /** Vrai si la personne connectee est l'administrateur (ou en dehors d'une requete, dans les tests de pages). */
    public static boolean isAdmin() {
        Chrome chrome = CHROME.get();
        return chrome == null || chrome.admin();
    }

    /**
     * Vrai si la personne connectee peut ouvrir cette page : sert a ne pas afficher un lien qui menerait a
     * « Acces reserve ». Les pages d'administration de l'immobilier sont reservees a l'administrateur ; les fiches
     * des biens et des prets a ceux qui consultent l'immobilier.
     */
    public static boolean canOpen(String href) {
        Chrome chrome = CHROME.get();
        if (chrome == null || chrome.admin() || href == null) return true;
        if (href.startsWith("/administration/bien") || href.startsWith("/administration/repartition") || href.startsWith("/administration/foyers")
                || href.startsWith("/administration/utilisateur") || href.startsWith("/apport/") || href.startsWith("/loyer/") || href.startsWith("/charge/")) return false;
        if (href.startsWith("/bien/") || href.startsWith("/pret/") || href.startsWith("/immobilier")) return chrome.realEstate();
        return true;
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

    /** Lien de navigation dont le libelle est raccourci sur smartphone ({@code short}). */
    private static Html shortNavLink(String href, String label, String shortLabel, boolean active) {
        return Html.of("<a href=\"%s\"%s aria-label=\"%s\"><span class=\"long-label\">%s</span><span class=\"short-label\" aria-hidden=\"true\">%s</span></a>",
                href, Html.raw(active ? " class=\"active\" aria-current=\"page\"" : ""), label, label, shortLabel);
    }

    public static Html navLink(String href, String label, boolean active) {
        return active ? Html.of("<a href=\"%s\" class=\"active\" aria-current=\"page\">%s</a>", href, label)
                : Html.of("<a href=\"%s\">%s</a>", href, label);
    }
}
