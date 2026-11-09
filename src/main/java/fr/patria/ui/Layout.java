package fr.patria.ui;

import java.util.List;

/** Squelette commun des pages : en-tete, navigation principale, contenu. */
public final class Layout {
    /** Entree active de la navigation principale. */
    public enum Section { HOME, BUDGET, WEALTH, REAL_ESTATE, SAVINGS, STOCKS, HEALTH, ASSISTANT, ADMINISTRATION, SECURITY, ACCOUNT, NONE }

    /** Foyer propose dans le selecteur de l'en-tete ; {@code managed} : la personne connectee en est gestionnaire. */
    public record Choice(long id, String name, boolean managed) {
    }

    /**
     * Ce que l'en-tete affiche pour la personne connectee : son nom, ses foyers (selecteur a partir de deux) et les
     * rubriques permises. Pose par le routeur pour la duree d'une requete.
     *
     * @param person     qui est connecte et ce qu'il peut ouvrir
     * @param households foyers du selecteur, {@code household} : celui qui est affiche
     * @param alerts     ce que l'en-tete signale
     * @param location   adresse de la page (chemin et requete), pour y revenir apres l'alerte HTTP
     */
    public record Chrome(Person person, List<Choice> households, long household, Alerts alerts, String location) {
        /**
         * @param administrator administrateur de l'application (foyers, utilisateurs)
         * @param manages       gestion complete du foyer affiche (utilisateur gestionnaire)
         * @param realEstate    consultation des biens du foyer
         */
        public record Person(String name, boolean administrator, boolean manages, boolean realEstate) {
        }

        /**
         * @param insecure  connexion en HTTP (hors poste local) et alerte pas encore lue dans cette session
         * @param audience  qui voit quoi dans ce foyer (icone d'information) ; vide pour l'administrateur
         * @param changes   changements d'acces depuis la derniere lecture
         * @param notice    avis a lire une fois, ou {@code null}
         */
        public record Alerts(boolean insecure, List<String> audience, List<String> changes, String notice) {
            public static final Alerts NONE = new Alerts(false, List.of(), List.of(), null);
        }

        public String name() {
            return person.name();
        }

        public boolean administrator() {
            return person.administrator();
        }

        public boolean manages() {
            return person.manages();
        }

        public boolean realEstate() {
            return person.realEstate();
        }

        public boolean insecure() {
            return alerts.insecure();
        }

        public List<String> audience() {
            return alerts.audience();
        }

        public List<String> changes() {
            return alerts.changes();
        }

        public String notice() {
            return alerts.notice();
        }
    }

    public static final ThreadLocal<Chrome> CHROME = new ThreadLocal<>();
    /** Nombre de biens et de prets du foyer affiche (pastilles du sous-menu Immobilier), calcule a la demande. */
    public static final ThreadLocal<java.util.function.Supplier<int[]>> ESTATE_COUNTS = new ThreadLocal<>();
    /** Theme force par la personne (« light » ou « dark ») ; vide : celui du systeme. */
    public static final ThreadLocal<String> THEME = new ThreadLocal<>();

    public static String theme(String cookie) {
        return "light".equals(cookie) || "dark".equals(cookie) ? cookie : "";
    }

    /** Groupes du menu d'administration : les comptes (tous les foyers), puis le contenu du foyer affiche. */
    public enum AdminGroup { ACCOUNTS, HOUSEHOLD }

    /** Qui voit une rubrique d'administration. */
    enum Who { ADMINISTRATOR, ESTATE, EVERYONE }

    /** Rubriques de l'administration, affichees en navigation laterale, par groupe, pour ceux qui peuvent les ouvrir. */
    public enum AdminSection {
        SECURITY(AdminGroup.ACCOUNTS, "/administration/securite", "Sécurité", Who.ADMINISTRATOR),
        HOUSEHOLDS(AdminGroup.ACCOUNTS, "/administration/foyers", "Foyers", Who.ADMINISTRATOR),
        USERS(AdminGroup.ACCOUNTS, "/administration/utilisateurs", "Utilisateurs", Who.ADMINISTRATOR),
        REQUESTS(AdminGroup.ACCOUNTS, "/administration/demandes", "Demandes de compte", Who.ADMINISTRATOR),
        ACCOUNT(AdminGroup.ACCOUNTS, "/compte", "Mon compte", Who.EVERYONE),
        INCOMES(AdminGroup.HOUSEHOLD, "/administration/revenus", "Revenus", Who.EVERYONE),
        EXPENSES(AdminGroup.HOUSEHOLD, "/administration/depenses", "Dépenses", Who.EVERYONE),
        TRANSFERS(AdminGroup.HOUSEHOLD, "/administration/virements", "Virements", Who.EVERYONE),
        PROPERTIES(AdminGroup.HOUSEHOLD, "/administration/biens", "Biens et prêts", Who.ESTATE),
        SHARES(AdminGroup.HOUSEHOLD, "/administration/repartition", "Répartition", Who.ESTATE);

        private final AdminGroup group;
        private final String href;
        private final String label;
        private final Who who;

        AdminSection(AdminGroup group, String href, String label, Who who) {
            this.group = group;
            this.href = href;
            this.label = label;
            this.who = who;
        }

        public String href() {
            return href;
        }

        /** L'administrateur n'a que les rubriques des comptes ; un utilisateur, celles de son foyer. */
        boolean shownTo(Chrome chrome) {
            if (chrome == null) return true;
            if (chrome.administrator()) return group == AdminGroup.ACCOUNTS;
            return switch (who) {
                case ADMINISTRATOR -> false;
                case ESTATE -> chrome.manages();
                case EVERYONE -> true;
            };
        }
    }

    /** Bascule de theme (systeme, clair, sombre), geree par app.js. */
    private static final Html THEME_TOGGLE = Html.raw("<button class=\"quiet theme-toggle\" type=\"button\" data-theme-toggle aria-label=\"Thème : système\" title=\"Thème : système\">"
            + "<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" "
            + "stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"8\"/><path d=\"M12 4a8 8 0 0 1 0 16z\" fill=\"currentColor\"/></svg></button>");
    private static final Html LOGOUT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/></svg>");

    private static final Html ACCOUNT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21a8 8 0 0 1 16 0\"/></svg>");

    private static final Html ASSISTANT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z\"/><path d=\"M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z\"/></svg>");
    private static final Html HEALTH_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8z\"/><path d=\"M3.5 12h4l2-3 3 6 2-3h6\"/></svg>");
    private static final Html INFO_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 16v-4\"/><path d=\"M12 8h.01\"/></svg>");
    private static final Html SHIELD_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/></svg>");
    private static final Html BURGER_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\"><path d=\"M4 6h16M4 12h16M4 18h16\"/></svg>");
    private static final Html SETTINGS_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z\"/></svg>");

    /** Pied de page : rappel de l'auto-hebergement, confidentialite, version et lien « Soutenir ». */
    private static Html footer() {
        Html support = BuildInfo.supportUrl() == null ? Html.EMPTY
                : Html.of("<a class=\"support-link\" href=\"%s\" target=\"_blank\" rel=\"noopener noreferrer\">♥ Soutenir Patria</a>", BuildInfo.supportUrl());
        return Html.of("""
                <footer class="shell site-footer"><span>Patria · vos données restent sur votre serveur</span><a href="/confidentialite">Confidentialité</a>\
                <span class="version" title="Version et commit de cette installation">v%s · %s</span>%s</footer>""",
                BuildInfo.version(), BuildInfo.shortCommit(), support);
    }

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
        return page(title, section, csrf, scope, content, null);
    }

    private static Html page(String title, Section section, String csrf, String scope, Html content, AdminSection active) {
        Chrome chrome = CHROME.get();
        boolean realEstate = chrome == null || chrome.realEstate();
        boolean administrator = chrome != null && chrome.administrator();
        if (active == null && chrome != null && chrome.location() != null) {
            String path = chrome.location().split("\\?", 2)[0];
            for (AdminSection candidate : AdminSection.values()) {
                if (path.equals(candidate.href) || path.startsWith(candidate.href + "/")) active = candidate;
            }
            if (path.startsWith("/administration/bien/")) active = AdminSection.PROPERTIES;
        }
        // Budget : une seule entree ; Previsionnel et Moyen se choisissent dans la barre de la page.
        Html budget = navLink("/" + scope, "Budget", section == Section.HOME || section == Section.BUDGET);
        Html manage = navLink("/administration", "Gérer", section == Section.ADMINISTRATION || section == Section.SECURITY);
        Html estate = realEstate ? navLink("/immobilier", "Immobilier", section == Section.REAL_ESTATE) : Html.EMPTY;
        Html follow = Html.of("""
                <a href="/bilan"%s aria-label="Bilan de santé" title="Bilan de santé">Bilan<span class="nav-count warning" data-warning-count hidden></span></a>
                <a href="/assistant"%s aria-label="Assistant" title="Assistant — Guide de saisie">%s<span class="menu-only">Assistant</span><span class="nav-count" data-guide-progress hidden></span></a>""",
                Html.raw(section == Section.HEALTH ? " class=\"active\" aria-current=\"page\"" : ""),
                Html.raw(section == Section.ASSISTANT ? " class=\"active\" aria-current=\"page\"" : ""), ASSISTANT_ICON);
        Html savings = Html.concat(navLink("/patrimoine", "Patrimoine", section == Section.WEALTH), navLink("/epargne", "Épargne", section == Section.SAVINGS),
                navLink("/bourse", "Bourse", section == Section.STOCKS));
        Html allLinks = administrator ? manage : Html.concat(budget, savings, estate, manage, follow);
        Html links = allLinks;
        // Tablette et smartphone : la navigation principale est remplacee par ce menu, rempli par le serveur avec les memes
        // entrees, pastille du Bilan et % de l'Assistant compris (utilisable sans JavaScript).
        Html burgerLinks = allLinks;
        Html burger = Html.of("""
                <details class="nav-menu nav-hamburger" name="navigation"><summary aria-label="Menu principal" title="Menu principal">%s</summary>\
                <div class="nav-panel"><nav class="nav-entries" aria-label="Menu principal">%s</nav></div></details>""", BURGER_ICON, burgerLinks);
        // Tablette et smartphone : le Bilan reste visible hors du menu, avec sa pastille d'alertes.
        Html health = administrator ? Html.EMPTY : Html.of("""
                <a class="nav-health%s" href="/bilan" aria-label="Bilan de santé" title="Bilan de santé">%s<span>Bilan</span><span class="nav-count warning" data-warning-count hidden></span></a>""",
                section == Section.HEALTH ? " active" : "", HEALTH_ICON);
        Html searchLinks = Html.concat(administrator ? Html.EMPTY : Html.concat(budget, estate,
                        navLink("/bilan", "Bilan de santé", false), navLink("/assistant", "Assistant — Guide de saisie", false),
                        realEstate ? Html.concat(navLink("/immobilier/prets", "Prêts", false), navLink("/immobilier/taxe-fonciere", "Taxe foncière", false),
                                Html.raw("<div data-property-links><a href=\"/immobilier/biens\">Mes biens</a></div>")) : Html.EMPTY),
                navLink("/administration", "Gérer", false), group(administrator ? AdminGroup.ACCOUNTS : AdminGroup.HOUSEHOLD, chrome, active), navLink("/compte", "Mon compte", false));
        Html header = csrf == null ? Html.EMPTY : Html.of("""
                <header class="topbar"%s><div class="shell navigation">%s%s<nav class="primary-navigation" aria-label="Navigation principale">%s</nav>
                <div class="account-bar">%s%s%s
                <form method="post" action="/deconnexion">%s<button class="quiet logout" type="submit" aria-label="Déconnexion" title="Déconnexion">%s</button></form>
                </div></div></header>""",
                Html.raw(administrator ? "" : " data-navigation-status"), brand(), Html.concat(burger, health), links, search(searchLinks), audience(chrome, csrf), Html.concat(THEME_TOGGLE, account(chrome, section == Section.ACCOUNT)),
                Components.csrfField(csrf), LOGOUT_ICON);
        Html overlay = csrf == null ? Html.EMPTY : SecurityPages.overlay(csrf);
        return document(title, "", Html.concat(header, Html.of("<main class=\"shell\">%s%s</main>", accountNotice(chrome, csrf), content), footer(), overlay));
    }

    /**
     * Selecteur du foyer affiche, a partir de deux foyers ; app.js l'envoie des qu'on change de foyer. Le bouton
     * « Afficher » n'existe que sans JavaScript ({@code noscript}) : rien ne clignote au chargement.
     */
    private static Html householdSwitch(Chrome chrome, String csrf) {
        if (chrome == null || chrome.administrator() || chrome.households().size() < 2) return Html.EMPTY;
        Html options = chrome.households().stream().map(choice -> Html.of("<option value=\"%s\"%s>%s</option>", choice.id(),
                choice.id() == chrome.household() ? Html.raw(" selected") : Html.EMPTY, choice.name())).collect(Html.joining());
        return Html.of("""
                <form method="post" action="/foyer/choisir" class="household-switch">%s<label><span class="visually-hidden">Foyer affiché</span>\
                <select name="foyer" data-autosubmit>%s</select></label><noscript><button class="quiet" type="submit">Afficher</button></noscript></form>""",
                Components.csrfField(csrf), options);
    }

    private static Html search(Html entries) {
        return Html.of("""
                <details class="nav-menu" name="navigation"><summary aria-label="Recherche globale" title="Recherche globale"><svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/></svg></summary><div class="nav-panel">
                <label class="nav-search"><span class="visually-hidden">Rechercher dans les pages et les biens accessibles</span><input type="search" placeholder="Rechercher partout…" autocomplete="off"></label>
                <div class="nav-entries">%s</div><p class="nav-empty muted" role="status" hidden>Aucun résultat.</p></div></details>""",
                entries);
    }

    /**
     * Icone d'information jaune « qui voit quoi » a cote du foyer : les comptes qui ont acces a chaque partie du foyer,
     * et les changements d'acces depuis la derniere lecture (pastille). Sans JavaScript : un {@code details}.
     */
    private static Html audience(Chrome chrome, String csrf) {
        if (chrome == null || chrome.administrator() || chrome.audience().isEmpty()) return Html.EMPTY;
        Html lines = chrome.audience().stream().map(line -> Html.of("<li>%s</li>", line)).collect(Html.joining());
        Html changes = chrome.changes().isEmpty() ? Html.EMPTY : Html.of("""
                <div class="audience-changes"><p class="eyebrow">Changements récents</p><ul>%s</ul>\
                <form method="post" action="/acces/vu">%s<input type="hidden" name="suite" value="%s"><button class="secondary" type="submit">J'ai vu</button></form></div>""",
                chrome.changes().stream().map(line -> Html.of("<li>%s</li>", line)).collect(Html.joining()), Components.csrfField(csrf),
                chrome.location() == null ? "/" : chrome.location());
        String label = chrome.changes().isEmpty() ? "Qui voit quoi dans ce foyer" : "Qui voit quoi dans ce foyer : accès modifiés récemment";
        return Html.of("""
                <details class="audience" name="navigation"><summary aria-label="%s" title="%s">%s%s</summary><div class="audience-panel" role="dialog" aria-label="Qui voit quoi">\
                <p class="eyebrow">Qui voit quoi · %s</p>%s<ul class="audience-lines">%s</ul>\
                <p class="muted small">L'administrateur crée les comptes et règle ces accès ; son compte administrateur ne voit aucune donnée financière dans l'interface. S'il ouvrait un accès,
                y compris à un compte à lui, il apparaîtrait ici.</p></div></details>""",
                label, label, INFO_ICON, chrome.changes().isEmpty() ? Html.EMPTY : Html.raw("<span class=\"audience-dot\" aria-hidden=\"true\"></span>"),
                householdName(chrome), changes, lines);
    }

    /** Avis a lire une fois (double authentification retiree par l'administrateur), avec « J'ai compris ». */
    private static Html accountNotice(Chrome chrome, String csrf) {
        if (chrome == null || chrome.notice() == null || csrf == null) return Html.EMPTY;
        return Html.of("""
                <section class="notice warning account-notice" role="alert"><span>%s</span><form method="post" action="/compte/avis-lu">%s\
                <button class="secondary" type="submit">J'ai compris</button></form></section>""", chrome.notice(), Components.csrfField(csrf));
    }

    /** Lien vers « Mon compte » (mot de passe), avec le nom de la personne connectee. */
    private static Html account(Chrome chrome, boolean active) {
        if (chrome == null) return Html.EMPTY;
        return Html.of("<a class=\"account-link%s\" href=\"/compte\"%s title=\"Mon compte\" aria-label=\"Mon compte (%s)\">%s<span>%s</span></a>",
                active ? " active" : "", Html.raw(active ? " aria-current=\"page\"" : ""), chrome.name(), ACCOUNT_ICON, chrome.name());
    }

    /** Rubrique de gestion ; la navigation commune indique la rubrique active. */
    public static Html adminSection(String title, String csrf, AdminSection active, Html content) {
        Chrome chrome = CHROME.get();
        Html body = active.group == AdminGroup.HOUSEHOLD ? Html.concat(householdBanner(chrome), content) : content;
        Html navigation = Html.concat(householdSwitch(chrome, csrf), group(chrome != null && chrome.administrator() ? AdminGroup.ACCOUNTS : AdminGroup.HOUSEHOLD, chrome, active));
        return page(title, active == AdminSection.SECURITY ? Section.SECURITY : Section.ADMINISTRATION, csrf, "",
                Html.of("<div class=\"admin-layout\"><aside class=\"admin-nav\" aria-label=\"Navigation de gestion\">%s</aside><div>%s</div></div>", navigation, body), active);
    }

    private static Html group(AdminGroup group, Chrome chrome, AdminSection active) {
        return java.util.Arrays.stream(AdminSection.values()).filter(section -> section != AdminSection.ACCOUNT && section.group == group && section.shownTo(chrome))
                .map(section -> navLink(section.href, section.label, section == active)).collect(Html.joining());
    }

    private static String householdName(Chrome chrome) {
        if (chrome == null) return "";
        return chrome.households().stream().filter(choice -> choice.id() == chrome.household()).map(Choice::name).findFirst().orElse("");
    }

    public static Html estateNavigation(String active) {
        java.util.function.Supplier<int[]> supplier = ESTATE_COUNTS.get();
        int[] counts = supplier == null ? null : supplier.get();
        return Html.of("<nav class=\"estate-navigation\" aria-label=\"Navigation immobilière\">%s%s%s%s</nav>",
                navLink("/immobilier", "Synthèse", active.equals("patrimoine")),
                countedLink("/immobilier/biens", "Mes biens", counts == null ? -1 : counts[0], active.equals("biens")),
                countedLink("/immobilier/prets", "Prêts", counts == null ? -1 : counts[1], active.equals("prets")),
                navLink("/immobilier/taxe-fonciere", "Taxe foncière", active.equals("taxe-fonciere")));
    }

    private static Html countedLink(String href, String label, int count, boolean active) {
        return Html.of("<a href=\"%s\"%s>%s%s</a>", href, Html.raw(active ? " class=\"active\" aria-current=\"page\"" : ""), label,
                count < 0 ? Html.EMPTY : Html.of(" <span class=\"nav-badge\">%s</span>", count));
    }

    /** Rappel du foyer modifie, en tete des pages qui en dependent (utile sur smartphone, ou le menu est replie). */
    private static Html householdBanner(Chrome chrome) {
        String name = householdName(chrome);
        if (name.isEmpty()) return Html.EMPTY;
        Html change = chrome.households().size() > 1
                ? Html.raw(chrome.administrator() ? " · <a href=\"/administration/foyers\">changer de foyer</a>" : " · changez de foyer dans Gérer") : Html.EMPTY;
        return Html.of("<p class=\"household-banner\">Foyer modifié : <b>%s</b>%s</p>", name, change);
    }

    /**
     * Vrai si la personne connectee gere le foyer affiche : l'administrateur ou un gestionnaire (ou en dehors d'une
     * requete, dans les tests de pages). Sert a montrer les boutons de modification.
     */
    public static boolean manages() {
        Chrome chrome = CHROME.get();
        return chrome == null || chrome.manages();
    }

    /**
     * Vrai si la personne connectee peut ouvrir cette page : sert a ne pas afficher un lien qui menerait a
     * « Acces reserve ». Foyers et utilisateurs : l'administrateur ; contenu du foyer : ceux qui le gerent ; fiches
     * des biens et des prets : ceux qui consultent l'immobilier.
     */
    public static boolean canOpen(String href) {
        Chrome chrome = CHROME.get();
        if (chrome == null || href == null) return true;
        if (href.startsWith("/administration/foyers") || href.startsWith("/administration/utilisateur") || href.startsWith("/administration/securite")) {
            return chrome.administrator();
        }
        if (chrome.administrator()) return href.startsWith("/compte") || href.startsWith("/confidentialite") || href.startsWith("/aide");
        if (chrome.manages()) return true;
        if (href.startsWith("/administration/bien") || href.startsWith("/administration/repartition")
                || href.startsWith("/administration/membres")
                || href.startsWith("/apport/") || href.startsWith("/loyer/") || href.startsWith("/charge/")) return false;
        if (href.startsWith("/bien/") || href.startsWith("/pret/") || href.startsWith("/immobilier")) return chrome.realEstate();
        return true;
    }

    /** Page d'administration d'un bien : navigation laterale + contenu, avec le rappel du foyer modifie. */
    public static Html adminPage(String title, String csrf, Html sideNav, Html content) {
        return page(title, Section.ADMINISTRATION, csrf, "", Html.of("<div class=\"admin-layout\">%s<div>%s%s</div></div>", sideNav, householdBanner(CHROME.get()), content), AdminSection.PROPERTIES);
    }

    public static Html message(String title, String text, String csrf) {
        return page(title + " · Patria", Section.NONE, csrf, Html.of("""
                <section class="card empty-state"><h1>%s</h1><p class="muted">%s</p>
                <a class="button secondary" href="/">Retour à l'accueil</a></section>""", title, text));
    }

    public static Html document(String title, String bodyClass, Html body) {
        return Html.of("""
                <!doctype html><html lang="fr"%s><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="%s"><style>html,body{background:#0a0e13;color:#edf3f8}                @media (prefers-color-scheme: light){html:not([data-theme=dark]),html:not([data-theme=dark]) body{background:#f5f7fa;color:#14202c}}                html[data-theme=light],html[data-theme=light] body{background:#f5f7fa;color:#14202c}</style>
                <title>%s</title><link rel="icon" type="image/png" href="%s"><link rel="stylesheet" href="%s"><script defer src="%s"></script></head>
                <body class="%s">%s</body></html>""",
                theme().isEmpty() ? Html.EMPTY : Html.of(" data-theme=\"%s\"", theme()), theme().isEmpty() ? "dark light" : theme(),
                title, Assets.url("/images/patria-logo.png"), Assets.url("/css/patria.css"), Assets.url("/js/app.js"), bodyClass, body);
    }

    private static String theme() {
        String theme = THEME.get();
        return theme == null ? "" : theme;
    }

    public static Html brand() {
        return Html.of("<a class=\"brand\" href=\"/\" aria-label=\"Patria — Prévisionnel\"><img class=\"brand-mark\" src=\"%s\" width=\"30\" height=\"30\" alt=\"\"><span>Patria</span></a>",
                Assets.url("/images/patria-logo.png"));
    }

    /** Icone accompagnee d'un libelle visible, aussi sur mobile. */
    private static Html iconLink(String href, String label, Html icon, boolean active) {
        return Html.of("<a href=\"%s\" class=\"nav-icon%s\"%s aria-label=\"%s\" title=\"%s\">%s<span>%s</span></a>", href, active ? " active" : "",
                Html.raw(active ? " aria-current=\"page\"" : ""), label, label, icon, label);
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
