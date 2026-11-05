package fr.patria.ui;

import java.util.List;

/** Squelette commun des pages : en-tete, navigation principale, contenu. */
public final class Layout {
    /** Entree active de la navigation principale. */
    public enum Section { HOME, BUDGET, REAL_ESTATE, HEALTH, ASSISTANT, ADMINISTRATION, SECURITY, ACCOUNT, NONE }

    /** Foyer propose dans le selecteur de l'en-tete ; {@code managed} : la personne connectee en est gestionnaire. */
    public record Choice(long id, String name, boolean managed) {
    }

    /**
     * Ce que l'en-tete affiche pour la personne connectee : son nom, ses foyers (selecteur a partir de deux) et les
     * rubriques permises. Pose par le routeur pour la duree d'une requete.
     *
     * @param administrator administrateur de l'application (foyers, utilisateurs)
     * @param manages       gestion complete du foyer affiche (l'administrateur, ou un utilisateur gestionnaire)
     * @param insecure      connexion en HTTP (hors poste local) et alerte pas encore lue dans cette session
     * @param location      adresse de la page (chemin et requete), pour y revenir apres l'alerte
     * @param audience      qui voit quoi dans ce foyer (icone d'information) ; vide pour l'administrateur
     * @param changes       changements d'acces depuis la derniere lecture
     * @param lastLogin     connexion precedente (« hier 21:12 · Lyon, France »), ou {@code null}
     * @param failures      echecs de connexion sur ce compte depuis
     * @param notice        avis a lire une fois, ou {@code null}
     */
    public record Chrome(String name, boolean administrator, boolean manages, boolean realEstate, List<Choice> households, long household,
                         boolean insecure, String location, List<String> audience, List<String> changes, String lastLogin, int failures, String notice) {
    }

    public static final ThreadLocal<Chrome> CHROME = new ThreadLocal<>();

    /** Groupes du menu d'administration : les comptes (tous les foyers), puis le contenu du foyer affiche. */
    public enum AdminGroup { ACCOUNTS, HOUSEHOLD }

    /** Qui voit une rubrique d'administration. */
    enum Who { ADMINISTRATOR, MANAGER, EVERYONE }

    /** Rubriques de l'administration, affichees en navigation laterale, par groupe, pour ceux qui peuvent les ouvrir. */
    public enum AdminSection {
        SECURITY(AdminGroup.ACCOUNTS, "/administration/securite", "Sécurité", Who.ADMINISTRATOR),
        HOUSEHOLDS(AdminGroup.ACCOUNTS, "/administration/foyers", "Foyers", Who.ADMINISTRATOR),
        USERS(AdminGroup.ACCOUNTS, "/administration/utilisateurs", "Utilisateurs", Who.ADMINISTRATOR),
        ACCOUNT(AdminGroup.ACCOUNTS, "/compte", "Mon compte", Who.EVERYONE),
        MEMBERS(AdminGroup.HOUSEHOLD, "/administration/membres", "Membres", Who.MANAGER),
        INCOMES(AdminGroup.HOUSEHOLD, "/administration/revenus", "Revenus", Who.EVERYONE),
        EXPENSES(AdminGroup.HOUSEHOLD, "/administration/depenses", "Dépenses", Who.EVERYONE),
        TRANSFERS(AdminGroup.HOUSEHOLD, "/administration/virements", "Virements", Who.EVERYONE),
        PROPERTIES(AdminGroup.HOUSEHOLD, "/administration/biens", "Biens et prêts", Who.MANAGER),
        SHARES(AdminGroup.HOUSEHOLD, "/administration/repartition", "Répartition", Who.MANAGER);

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
                case MANAGER -> chrome.manages();
                case EVERYONE -> true;
            };
        }
    }

    private static final Html LOGOUT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4\"/><path d=\"M16 17l5-5-5-5\"/><path d=\"M21 12H9\"/></svg>");

    private static final Html ACCOUNT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21a8 8 0 0 1 16 0\"/></svg>");

    private static final Html ASSISTANT_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z\"/><path d=\"M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z\"/></svg>");
    private static final Html HEALTH_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8z\"/><path d=\"M3.5 12h4l2-3 3 6 2-3h6\"/></svg>");
    private static final Html CLOCK_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v5l3 2\"/></svg>");
    private static final Html INFO_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 16v-4\"/><path d=\"M12 8h.01\"/></svg>");
    private static final Html SHIELD_ICON = Html.raw("<svg aria-hidden=\"true\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/></svg>");
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
        Chrome chrome = CHROME.get();
        boolean realEstate = chrome == null || chrome.realEstate();
        boolean administrator = chrome != null && chrome.administrator();
        Html links = administrator
                // L'administrateur n'a pas de budget : ses seules pages sont celles des comptes.
                ? Html.concat(shortNavLink("/administration/foyers", "Foyers", "Foyers", section == Section.ADMINISTRATION),
                        iconLink("/administration/securite", "Sécurité", SHIELD_ICON, section == Section.SECURITY))
                : Html.concat(shortNavLink("/" + scope, "Prévisionnel", "Prévision", section == Section.HOME),
                        shortNavLink("/budget" + scope, "Budget moyen", "Moyenne", section == Section.BUDGET),
                        realEstate ? shortNavLink("/immobilier", "Immobilier", "Immo", section == Section.REAL_ESTATE) : Html.EMPTY,
                        iconLink("/bilan", "Bilan de santé", HEALTH_ICON, section == Section.HEALTH),
                        iconLink("/assistant", "Guide de saisie", ASSISTANT_ICON, section == Section.ASSISTANT),
                        iconLink("/administration", "Administration", SETTINGS_ICON, section == Section.ADMINISTRATION));
        Html header = csrf == null ? Html.EMPTY : Html.of("""
                <header class="topbar"><div class="shell navigation">%s<nav>%s</nav><div class="account-bar">%s%s%s
                <form method="post" action="/deconnexion">%s<button class="quiet logout" type="submit" aria-label="Déconnexion" title="Déconnexion">%s<span>Déconnexion</span></button></form>
                </div></div></header>""",
                brand(), links, Html.concat(lastLogin(chrome), audience(chrome, csrf)), householdSwitch(chrome, csrf), account(chrome, section == Section.ACCOUNT),
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
                <details class="audience"><summary aria-label="%s" title="%s">%s%s</summary><div class="audience-panel" role="dialog" aria-label="Qui voit quoi">\
                <p class="eyebrow">Qui voit quoi · %s</p>%s<ul class="audience-lines">%s</ul>\
                <p class="muted small">L'administrateur crée les comptes et règle ces accès ; son compte administrateur ne voit aucune donnée financière dans l'interface. S'il ouvrait un accès,
                y compris à un compte à lui, il apparaîtrait ici.</p></div></details>""",
                label, label, INFO_ICON, chrome.changes().isEmpty() ? Html.EMPTY : Html.raw("<span class=\"audience-dot\" aria-hidden=\"true\"></span>"),
                householdName(chrome), changes, lines);
    }

    /**
     * Connexion precedente, dans l'en-tete : « Dernière connexion : hier 21:12 · Lyon » ; en orange s'il y a eu des
     * echecs sur ce compte depuis. Le detail est dans « Mon compte ».
     */
    private static Html lastLogin(Chrome chrome) {
        if (chrome == null || chrome.lastLogin() == null) return Html.EMPTY;
        String failures = chrome.failures() == 0 ? "" : chrome.failures() + (chrome.failures() > 1 ? " échecs" : " échec") + " depuis";
        String title = "Dernière connexion : " + chrome.lastLogin() + (failures.isEmpty() ? "" : ". " + failures + " sur votre compte (mot de passe ou code faux).")
                + " Détail dans Mon compte.";
        return Html.of("<a class=\"last-login%s\" href=\"/compte#connexions\" title=\"%s\">%s<span>%s</span>%s</a>", chrome.failures() > 0 ? " warn" : "", title,
                CLOCK_ICON, chrome.lastLogin().equals("première connexion") ? "Première connexion" : "Dernière connexion : " + chrome.lastLogin(),
                failures.isEmpty() ? Html.EMPTY : Html.of("<b>%s</b>", failures));
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

    /**
     * Page d'une rubrique d'administration avec sa navigation laterale en deux groupes : « Comptes » (foyers,
     * utilisateurs : tous les foyers) et « Foyer : Martin » (ce qu'il contient). Chacun ne voit que ses rubriques.
     */
    public static Html adminSection(String title, String csrf, AdminSection active, Html content) {
        Chrome chrome = CHROME.get();
        boolean admin = chrome == null || chrome.administrator();
        Html accounts = group(AdminGroup.ACCOUNTS, chrome, active);
        Html household = group(AdminGroup.HOUSEHOLD, chrome, active);
        String name = householdName(chrome);
        Html householdTitle = Html.of("<p class=\"eyebrow nav-group\">Foyer%s</p>", name.isEmpty() ? Html.EMPTY : Html.of(" · <span>%s</span>", name));
        Html accountsTitle = Html.of("<p class=\"eyebrow nav-group\">%s</p>", admin ? "Comptes" : "Mon compte");
        // L'administrateur n'a pas de groupe « Foyer » : il ne voit pas le contenu des foyers.
        Html nav = chrome != null && chrome.administrator() ? Html.concat(accountsTitle, accounts)
                : admin ? Html.concat(accountsTitle, accounts, householdTitle, household) : Html.concat(householdTitle, household, accountsTitle, accounts);
        Html body = active.group == AdminGroup.HOUSEHOLD ? Html.concat(householdBanner(chrome), content) : content;
        return page(title, active == AdminSection.SECURITY ? Section.SECURITY : Section.ADMINISTRATION, csrf,
                Html.of("<div class=\"admin-layout\"><aside class=\"admin-nav\">%s</aside><div>%s</div></div>", nav, body));
    }

    private static Html group(AdminGroup group, Chrome chrome, AdminSection active) {
        return java.util.Arrays.stream(AdminSection.values()).filter(section -> section.group == group && section.shownTo(chrome))
                .map(section -> navLink(section.href, section.label, section == active)).collect(Html.joining());
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
                ? Html.raw(chrome.administrator() ? " · <a href=\"/administration/foyers\">changer de foyer</a>" : " · changez de foyer dans l'en-tête") : Html.EMPTY;
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

    /** Lien de navigation sous forme d'icone, avec son nom en info-bulle et pour les lecteurs d'ecran. */
    private static Html iconLink(String href, String label, Html icon, boolean active) {
        return Html.of("<a href=\"%s\" class=\"nav-icon%s\"%s aria-label=\"%s\" title=\"%s\">%s</a>", href, active ? " active" : "",
                Html.raw(active ? " aria-current=\"page\"" : ""), label, label, icon);
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
