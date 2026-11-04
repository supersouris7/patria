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

    /** Groupes du menu d'administration : les comptes (tous les foyers), puis le contenu du foyer affiche. */
    public enum AdminGroup { ACCOUNTS, HOUSEHOLD }

    /**
     * Rubriques de l'administration, affichees en navigation laterale, par groupe ; {@code userLabel} : nom pour un
     * utilisateur, {@code null} si reservee a l'administrateur.
     */
    public enum AdminSection {
        HOUSEHOLDS(AdminGroup.ACCOUNTS, "/administration/foyers", "Foyers", null),
        USERS(AdminGroup.ACCOUNTS, "/administration/utilisateurs", "Utilisateurs", null),
        ACCOUNT(AdminGroup.ACCOUNTS, "/compte", "Mon compte", "Mon compte"),
        MEMBERS(AdminGroup.HOUSEHOLD, "/administration/membres", "Membres", null),
        INCOMES(AdminGroup.HOUSEHOLD, "/administration/revenus", "Revenus", "Revenus"),
        EXPENSES(AdminGroup.HOUSEHOLD, "/administration/depenses", "Dépenses", "Dépenses"),
        TRANSFERS(AdminGroup.HOUSEHOLD, "/administration/virements", "Virements", "Virements"),
        PROPERTIES(AdminGroup.HOUSEHOLD, "/administration/biens", "Biens et prêts", null),
        SHARES(AdminGroup.HOUSEHOLD, "/administration/repartition", "Répartition", null);

        private final AdminGroup group;
        private final String href;
        private final String label;
        private final String userLabel;

        AdminSection(AdminGroup group, String href, String label, String userLabel) {
            this.group = group;
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

    /**
     * Page d'une rubrique d'administration avec sa navigation laterale en deux groupes : « Comptes » (foyers,
     * utilisateurs : tous les foyers) et « Foyer : Martin » (ce qu'il contient). Un utilisateur ne voit que ses rubriques.
     */
    public static Html adminSection(String title, String csrf, AdminSection active, Html content) {
        Chrome chrome = CHROME.get();
        boolean admin = chrome == null || chrome.admin();
        Html accounts = group(AdminGroup.ACCOUNTS, admin, active);
        Html household = group(AdminGroup.HOUSEHOLD, admin, active);
        String name = householdName(chrome);
        Html householdTitle = Html.of("<p class=\"eyebrow nav-group\">Foyer%s</p>", name.isEmpty() ? Html.EMPTY : Html.of(" · <span>%s</span>", name));
        Html accountsTitle = Html.of("<p class=\"eyebrow nav-group\">%s</p>", admin ? "Comptes" : "Mon compte");
        Html nav = admin ? Html.concat(accountsTitle, accounts, householdTitle, household) : Html.concat(householdTitle, household, accountsTitle, accounts);
        Html body = active.group == AdminGroup.HOUSEHOLD ? Html.concat(householdBanner(chrome), content) : content;
        return page(title, Section.ADMINISTRATION, csrf, Html.of("<div class=\"admin-layout\"><aside class=\"admin-nav\">%s</aside><div>%s</div></div>", nav, body));
    }

    private static Html group(AdminGroup group, boolean admin, AdminSection active) {
        return java.util.Arrays.stream(AdminSection.values()).filter(section -> section.group == group && (admin || section.userLabel != null))
                .map(section -> navLink(section.href, admin ? section.label : section.userLabel, section == active)).collect(Html.joining());
    }

    private static String householdName(Chrome chrome) {
        if (chrome == null) return "";
        return chrome.households().stream().filter(choice -> choice.id() == chrome.household()).map(Choice::name).findFirst().orElse("");
    }

    /** Rappel du foyer modifie, en tete des pages qui en dependent (utile sur smartphone, ou le menu est replie). */
    private static Html householdBanner(Chrome chrome) {
        String name = householdName(chrome);
        if (name.isEmpty()) return Html.EMPTY;
        Html change = chrome.households().size() > 1
                ? Html.raw(chrome.admin() ? " · <a href=\"/administration/foyers\">changer de foyer</a>" : " · changez de foyer dans l'en-tête") : Html.EMPTY;
        return Html.of("<p class=\"household-banner\">Foyer modifié : <b>%s</b>%s</p>", name, change);
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
                || href.startsWith("/administration/membres")
                || href.startsWith("/administration/utilisateur") || href.startsWith("/apport/") || href.startsWith("/loyer/") || href.startsWith("/charge/")) return false;
        if (href.startsWith("/bien/") || href.startsWith("/pret/") || href.startsWith("/immobilier")) return chrome.realEstate();
        return true;
    }

    /** Page d'administration d'un bien : navigation laterale + contenu, avec le rappel du foyer modifie. */
    public static Html adminPage(String title, String csrf, Html sideNav, Html content) {
        return page(title, Section.ADMINISTRATION, csrf, Html.of("<div class=\"admin-layout\">%s<div>%s%s</div></div>", sideNav, householdBanner(CHROME.get()), content));
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
