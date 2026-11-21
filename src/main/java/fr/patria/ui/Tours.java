package fr.patria.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Visites guidees : une par page, lancee seule a la premiere ouverture, retenue par compte une fois finie ou passee.
 * Elles sont ecrites pour quelqu'un qui ne connait pas du tout Patria : chaque page commence par une bulle qui dit a
 * quoi elle sert, puis une bulle par fonctionnalite, posee sur le vrai element de l'ecran (flèche animee et halo).
 *
 * <p>Cible d'une etape : un selecteur CSS, suivi si besoin de {@code |texte} (le premier element qui contient ce
 * texte). Un titre designe sa carte entiere. Une etape dont la cible n'est pas affichee (page vide, droits) est sautee
 * par app.js ; la premiere bulle de chaque page, sans cible, decrit donc tout, meme sur une page encore vide.
 */
public final class Tours {
    /** Barre du haut : vue une fois, avant la visite de la premiere page ouverte. */
    public static final String BAR = "barre";
    /** Visite de l'administrateur (ses rubriques), sur la premiere page d'administration ouverte. */
    public static final String ADMIN = "admin";

    /** Une bulle : cible (vide : au centre de l'ecran), titre et texte. */
    record Stop(String target, String title, String body) {
    }

    private Tours() {
    }

    /** Toutes les visites : seules celles-ci sont retenues (le navigateur ne peut rien stocker d'autre). */
    private static final Set<String> KEYS = Set.of(BAR, ADMIN, "previsionnel", "budget-moyen", "patrimoine", "epargne", "bourse", "bourse-import",
            "bourse-ajout", "immobilier", "biens", "prets", "taxe-fonciere", "bien", "pret", "bien-modifier", "pret-nouveau", "pret-gerer", "economies",
            "bilan", "guide", "documents", "revue-annuelle", "foyer", "revenus", "depenses", "virements", "repartition", "gerer-biens", "ligne", "compte");

    public static boolean known(String key) {
        return KEYS.contains(key);
    }

    /** Visite de la page ({@code path} sans requete), ou {@code null} si elle n'en a pas. */
    static String key(String path, boolean administrator) {
        if (administrator) return path.startsWith("/administration") ? ADMIN : null;
        if (path.matches("/bien/\\d+")) return "bien";
        if (path.matches("/pret/\\d+")) return "pret";
        if (path.matches("/pret/\\d+/gerer")) return "pret-gerer";
        if (path.matches("/bien/\\d+/prets/nouveau")) return "pret-nouveau";
        if (path.matches("/administration/bien/(\\d+|nouveau)")) return "bien-modifier";
        if (path.matches("/(depense|revenu|virement)/\\d+")) return "ligne";
        return switch (path) {
            case "/" -> "previsionnel";
            case "/budget" -> "budget-moyen";
            case "/patrimoine" -> "patrimoine";
            case "/epargne" -> "epargne";
            case "/bourse" -> "bourse";
            case "/bourse/importer" -> "bourse-import";
            case "/bourse/ajouter" -> "bourse-ajout";
            case "/assistant/documents" -> "documents";
            case "/assistant/revue-annuelle" -> "revue-annuelle";
            case "/immobilier" -> "immobilier";
            case "/immobilier/biens" -> "biens";
            case "/immobilier/prets" -> "prets";
            case "/immobilier/taxe-fonciere" -> "taxe-fonciere";
            case "/economies" -> "economies";
            case "/bilan" -> "bilan";
            case "/assistant" -> "guide";
            case "/foyer" -> "foyer";
            case "/administration/revenus" -> "revenus";
            case "/administration/depenses" -> "depenses";
            case "/administration/virements" -> "virements";
            case "/administration/repartition" -> "repartition";
            case "/administration/biens" -> "gerer-biens";
            case "/compte" -> "compte";
            default -> null;
        };
    }

    /**
     * Gabarits de visite de cette page. Seule la barre du haut (ou l'administration) se lance d'elle-meme, une fois ou
     * avec {@code ?visite=1} ; la visite de la page attend un clic sur le bouton « ? » de son titre.
     */
    static Html template(Layout.Chrome chrome) {
        String[] location = chrome.location().split("\\?", 2);
        boolean forced = location.length > 1 && location[1].matches("(.*&)?visite=1(&.*)?");
        String page = key(location[0], chrome.administrator());
        if (page == null) return Html.EMPTY;
        Set<String> seen = chrome.tours();
        String auto = chrome.administrator() ? ADMIN : BAR;
        Html automatic = Html.EMPTY;
        if (forced || !seen.contains(auto)) {
            // Apres la barre du haut : un bouton vers le Guide de saisie, sauf si on y est deja.
            boolean home = !chrome.administrator() && !"guide".equals(page);
            automatic = Html.of("<template id=\"visite\" data-tours=\"%s\" data-final-href=\"%s\" data-final-label=\"%s\">%s</template>", auto,
                    home ? "/assistant" : "", home ? "Ouvrir le Guide de saisie" : "", stops(chrome.administrator() ? admin() : bar(chrome)));
        }
        List<Stop> own = chrome.administrator() ? admin() : page(page, chrome);
        return Html.concat(automatic, own.isEmpty() ? Html.EMPTY : Html.of("<template id=\"visite-page\">%s</template>", stops(own)));
    }

    private static Html stops(List<Stop> stops) {
        return stops.stream().map(stop -> Html.of("<div data-stop data-target=\"%s\"><h2>%s</h2><p>%s</p></div>", stop.target(), stop.title(), stop.body()))
                .collect(Html.joining());
    }

    private static Stop stop(String target, String title, String body) {
        return new Stop(target, title, body);
    }

    private static List<Stop> admin() {
        return List.of(
                stop("", "Bienvenue dans l'administration", "Vous installez et dépannez Patria. Vous ne voyez aucun montant : les finances restent à chaque foyer."),
                stop(".admin-nav a[href='/administration/foyers']", "Foyers",
                        "Tous les foyers, leurs membres et gestionnaires, sans aucun montant. Un foyer sans gestionnaire est signalé ici : créez-lui un lien d'invitation."),
                stop(".admin-nav a[href='/administration/utilisateurs']", "Utilisateurs",
                        "Recherche, filtres et actions groupées : réinitialiser un mot de passe, retirer une double authentification, désactiver un compte."),
                stop(".admin-nav a[href='/administration/installation']", "Installation",
                        "Inscriptions privées ou publiques, adresse publique, envoi des e-mails, conditions d'utilisation et mentions légales."),
                stop(".admin-nav a[href='/administration/securite']", "Sécurité",
                        "Connexions, blocages, envoi d'e-mails, journal des droits et état du serveur."),
                stop(".account-bar > .theme-toggle", "Clair ou sombre", "Ce bouton passe du thème de l'appareil au thème clair, puis sombre."),
                stop("", "Vous supervisez, sans saisir",
                        "Vous ne créez aucun compte : chacun s'inscrit ou est invité par le gestionnaire de son foyer, qui règle qui voit quoi."));
    }

    /** La barre du haut, la meme sur toutes les pages. */
    private static List<Stop> bar(Layout.Chrome chrome) {
        boolean manager = chrome.householdManager();
        List<Stop> stops = new ArrayList<>();
        stops.add(stop(".nav-hamburger > summary, .primary-navigation", "Bienvenue : le menu",
                "Patria vous dit combien il vous reste chaque mois et suit ce que vous possédez. Toutes les pages sont ici "
                        + "(sur téléphone, la recherche et Mon compte aussi)."));
        stops.add(stop(".add-menu > summary", "Ajouter",
                "Le seul bouton pour saisir quelque chose : une dépense, un revenu, un relevé bancaire, un placement… "
                        + "Vous pouvez aussi glisser le fichier de votre banque sur n'importe quelle page."));
        stops.add(stop(".nav-search-menu > summary", "Recherche", "Tapez le nom d'une page ou d'un bien pour y aller directement."));
        stops.add(stop(".account-bar > .account-link", "Mon compte", "Votre profil, votre mot de passe et la double authentification."));
        stops.add(stop(".audience > summary", "Qui voit quoi", manager
                ? "Ce que chaque compte du foyer voit. Vous le réglez dans Gérer → Mon foyer et les droits."
                : "Ce que chaque compte du foyer voit, réglé par le gestionnaire."));
        stops.add(stop("", "Par où commencer ?", (manager
                ? "Le Guide de saisie remplit Patria pas à pas : avec le fichier de votre banque, avec une IA, ou à la main. "
                : "Le Guide de saisie vous accompagne pas à pas pour vos propres revenus et dépenses. ")
                + "Besoin d'aide sur une page ? Cliquez sur le « ? » à côté de son titre."));
        return stops;
    }

    private static List<Stop> page(String page, Layout.Chrome chrome) {
        String pdf = ".heading-actions a[href$='.pdf']";
        return switch (page) {
            case "previsionnel" -> List.of(
                    stop("", "Le Budget prévisionnel",
                            "Cette page répond à : combien me restera-t-il ce mois-ci, et les 12 prochains ? Les dépenses qui ne tombent pas tous les mois "
                                    + "(assurance annuelle, Noël, vacances) apparaissent le mois où elles tombent."),
                    stop(".budget-bar .segmented", "Prévisionnel ou Moyen",
                            "Prévisionnel : mois par mois. Moyen : un mois type, toutes les dépenses ramenées au mois, avec ce qu'il faut mettre de côté."),
                    stop(".scope-tabs", "Pour qui ?", "Le budget de tout le foyer, d'une seule personne ou du compte joint."),
                    stop("section.metrics", "Les chiffres du mois",
                            "Revenus, dépenses, reste à vivre (ce qui reste après tout) et taux d'endettement (la part des revenus qui part en crédits, "
                                    + "à garder sous 33 %). Le petit « i » explique chaque chiffre."),
                    stop(".watch", "À surveiller", "Les mois difficiles à venir, pour les anticiper."),
                    stop(".chart-scroll", "Les 12 prochains mois",
                            "Une barre par mois. Cliquez dessus : le détail du mois s'ouvre en dessous, et chaque ligne s'ouvre pour être modifiée."),
                    stop(".guide-strip", "Continuer le guide", "Tant que le guide n'est pas fini, cette barre propose de reprendre là où vous en étiez. « × » la masque."),
                    stop(".health-summary", "Points du Bilan", "Un rappel des points à corriger trouvés par le Bilan de santé."),
                    stop(".review-reminder", "Revue annuelle", "Une fois par an, Patria vous propose de mettre à jour vos montants en une seule fois."),
                    stop(pdf, "Exporter en PDF", "Une synthèse sur une page A4, faite sur votre serveur, à garder ou imprimer."),
                    stop(".heading-actions a[href='/administration/depenses']", "Gérer les dépenses", "La liste complète de vos dépenses, pour en modifier ou supprimer."));
            case "budget-moyen" -> List.of(
                    stop("", "Le Budget moyen", "Un mois type : toutes les dépenses ramenées au mois, même celles qui ne tombent qu'une fois par an."),
                    stop("section.metrics", "À mettre de côté",
                            "Combien mettre de côté chaque mois pour payer sans stress les grosses dépenses qui ne tombent pas tous les mois."),
                    stop(".rank-group", "Dépenses par catégorie", "Où part votre argent, de la plus grosse catégorie à la plus petite. Cliquez une catégorie pour voir son détail."),
                    stop("table.sortable", "Les listes", "Revenus et dépenses en détail. Cliquez un titre de colonne pour trier, une ligne pour la modifier."),
                    stop("section.disclosure|taux d'endettement", "Taux d'endettement", "Le détail du calcul : vos crédits face à vos revenus."),
                    stop(pdf, "Exporter en PDF", "Une synthèse sur une page A4, à garder ou imprimer."));
            case "patrimoine" -> List.of(
                    stop("", "Votre patrimoine", "Qu'est-ce que je possède vraiment ? Tout ce que vous avez, moins ce que vous devez encore."),
                    stop(".wealth-tabs", "Les onglets",
                            "Vue d'ensemble, puis le détail : Épargne (livrets), Bourse (actions, cryptos)" + (chrome.realEstate() ? " et Immobilier." : ".")),
                    stop("section.metrics", "Trois chiffres",
                            "Ce que vous possédez, ce que vous devez, et le patrimoine net : ce qui vous resterait si vous remboursiez tout aujourd'hui."),
                    stop(".wealth-bar:not(.debt-bar)", "La répartition", "Comment votre patrimoine se partage entre comptes, livrets, bourse et immobilier."),
                    stop(".debt-bar", "Vos prêts", "La part déjà remboursée de vos prêts, le taux moyen et la date de fin du dernier."),
                    stop(".wealth-table", "Le détail", "Chaque ligne de ce que vous possédez, avec son montant."),
                    stop("section.disclosure|Comment c'est calculé", "Comment c'est calculé", "Ce qui est compté, et ce qui ne l'est pas (frais de vente, crédits à la consommation)."));
            case "epargne" -> List.of(
                    stop("", "Votre épargne",
                            "Comptes courants, Livret A, LDDS, LEP, PEL, assurance-vie, PER… Ajoutez un placement par « + Ajouter », puis mettez son solde à jour quand il change."),
                    stop("section.metrics", "Les tuiles",
                            "Épargne totale, épargne de précaution (combien de mois de dépenses vous tiendriez), hausse des prix sur un an et vos intérêts. "
                                    + "En rouge : votre épargne perd du pouvoir d'achat."),
                    stop(".savings-table", "Vos comptes", "Chaque placement, son solde et son taux. Les taux des livrets réglementés sont fournis par Patria."),
                    stop("details.row-menu > summary", "Gérer un compte",
                            "Mettre à jour le solde, changer le taux, programmer un versement chaque mois (le solde avance tout seul) ou supprimer le compte."),
                    stop("#plan", "Plan d'épargne de sécurité",
                            "L'objectif (6 mois de dépenses par défaut), ce qui manque, combien verser chaque mois et dans quel ordre remplir vos livrets."),
                    stop("h2|Historique de l'épargne", "Historique", "Vos 24 derniers mois en barres : disponible, bourse, long terme."),
                    stop("h2|Hausse des prix", "Hausse des prix et taux des livrets", "Sur deux ans, la hausse des prix comparée au Livret A, au LEP et à votre taux moyen."),
                    stop("section.disclosure|Revenu fiscal", "Revenu fiscal de référence", "Indiquez-le (il est sur votre avis d'impôt) : Patria saura seul si vous avez droit au LEP."));
            case "bourse" -> List.of(
                    stop("", "Votre bourse",
                            "PEA, comptes-titres et cryptos, à part parce que leur valeur peut baisser. Ajoutez un titre par « + Ajouter » ou importez le relevé de votre courtier."),
                    stop(".heading-actions a[href='/bourse/importer']", "Importer un relevé", "Déposez le fichier de votre courtier tel quel (Trade Republic, Degiro, BoursoBank, Binance…) : tout se remplit."),
                    stop("section.metrics", "Les tuiles",
                            "Valeur au cours du jour, gain si vous vendiez aujourd'hui (rien n'est gagné ni perdu tant que vous ne vendez pas), montant investi et argent non placé."),
                    stop("h2|Est-ce que vos efforts", "Est-ce que ça vaut le coup ?",
                            "Votre portefeuille comparé à un repère simple (MSCI World, CAC 40, Livret A…), sur 1 mois, 1 an ou 5 ans, avec une phrase de verdict."),
                    stop(".positions-table", "Vos titres", "Chaque titre, sa quantité, son prix moyen et sa valeur. « Courbe du cours » montre son évolution."),
                    stop("details.row-menu > summary", "Gérer un compte", "Mettre à jour le compte, programmer un versement chaque mois ou le supprimer."));
            case "bourse-ajout" -> List.of(
                    stop("", "Ajouter un titre", "Deux étapes : trouver le titre, puis indiquer combien de parts vous avez et à quel prix moyen vous les avez achetées."),
                    stop("form.title-search", "Trouver le titre", "Tapez son nom, son ISIN ou son code : « MSCI World », « FR0010315770 », « BTC »."),
                    stop("summary|pas trouvé", "Pas trouvé ?", "Saisissez directement son code de cotation."));
            case "bourse-import" -> List.of(
                    stop("", "Importer un relevé de bourse", "Déposez le fichier exporté depuis le site de votre courtier : Patria reconnaît les colonnes et montre un aperçu avant d'enregistrer."),
                    stop("summary|Comment obtenir l'export", "Où trouver le fichier ?", "Les étapes, courtier par courtier, pour télécharger le bon export."),
                    stop("summary|Nouveau compte", "Nouveau compte", "Si le relevé concerne un compte pas encore dans Patria : son type, son nom et son titulaire."),
                    stop("summary|Passer par une IA", "Seulement un PDF ?", "Une consigne à donner à une IA pour transformer le PDF en fichier lisible."),
                    stop(".form-actions button", "Voir l'aperçu", "Rien n'est enregistré avant que vous validiez l'aperçu. Redéposer un export récent met les lignes à jour."));
            case "immobilier" -> List.of(
                    stop("", "Votre immobilier", "Ce que valent vos biens, ce que vous devez encore, et ce qu'ils vous ont vraiment rapporté. Ajoutez un bien par « + Ajouter »."),
                    stop(".estate-navigation", "Les sous-pages", "Synthèse, Mes biens (une fiche par bien), Prêts et Taxe foncière."),
                    stop("section.metrics", "Les chiffres",
                            "Valeur estimée, valeur nette (ce qui resterait après la vente et le remboursement), enrichissement réel depuis l'achat et prochaine mensualité."),
                    stop("h2|Bilan", "Le bilan", "Chaque ligne du calcul. Cliquez sur un « ? » pour comprendre d'où vient le chiffre."),
                    stop(pdf, "Exporter en PDF", "Une synthèse sur une page A4, à garder ou imprimer."));
            case "biens" -> List.of(
                    stop("", "Mes biens", "La liste de vos biens immobiliers. Chacun a sa fiche : valeur, prix du quartier, prêts, charges et loyers."),
                    stop(".property-button", "Ouvrir une fiche", "Cliquez sur un bien pour voir tout son détail."));
            case "prets" -> List.of(
                    stop("", "Vos prêts", "Tous vos prêts immobiliers : ce qu'il reste à rembourser, les mensualités et les intérêts encore à payer."),
                    stop("section.metrics", "Les chiffres", "Reste à rembourser, mensualités, intérêts restant à payer et date de fin du dernier prêt."),
                    stop("table.sortable", "La liste", "Cliquez sur un prêt pour son échéancier et la comparaison avec les taux actuels."));
            case "taxe-fonciere" -> List.of(
                    stop("", "Taxe foncière",
                            "Le montant de chaque bien, année par année. Une seule année suffit : Patria la revalorise chaque année. Qui la paie se règle dans Gérer → Répartition."));
            case "bien-modifier" -> List.of(
                    stop("", "Fiche du bien à remplir", "Les informations du bien : plus vous en donnez, plus les calculs sont justes."),
                    stop(".admin-nav", "Les autres onglets", "Prêts et apports, Charges (taxe foncière, copropriété, assurance) et Occupation (location ou habitation)."),
                    stop("h2|Acquisition", "Acquisition", "Le prix d'achat, les frais de notaire et la date : ils servent à calculer ce que le bien vous a rapporté."),
                    stop("h2|Estimation", "Estimation", "Une valeur prudente et une optimiste. Patria les actualise ensuite avec les ventes du département."),
                    stop("h2|Valeur nette", "Valeur nette", "Les frais de vente (agence, impôt sur la plus-value) déduits pour savoir ce qui vous resterait vraiment."));
            case "pret-nouveau" -> List.of(
                    stop("", "Ajouter un prêt",
                            "Patria a besoin du tableau d'amortissement de la banque (toutes les échéances). Il est dans votre espace client ou dans l'offre de prêt."),
                    stop("input[type=file]", "Le fichier", "Un fichier CSV. Vous n'avez que le PDF ? Une consigne pour IA le convertit ; vérifiez ensuite les dates et les montants."),
                    stop("details.help-block > summary", "Format du fichier", "Les colonnes attendues, si vous préparez le fichier vous-même."));
            case "pret-gerer" -> List.of(
                    stop("", "Gérer le prêt", "Renommer le prêt, remplacer son échéancier (après une renégociation par exemple) ou le supprimer."),
                    stop("h2|Remplacer l'échéancier", "Remplacer l'échéancier", "Un nouveau fichier remplace toutes les anciennes lignes de ce prêt."));
            case "ligne" -> List.of(
                    stop("", "Modifier une ligne", "Le libellé, le montant, la fréquence, les dates et la répartition."),
                    stop("label|valable à partir", "Le montant change ?",
                            "Indiquez la date du nouveau montant : l'ancien reste avant cette date, pour l'historique. Videz ce champ pour corriger une simple faute de frappe."),
                    stop("fieldset.shares", "Répartition", "La part de chaque membre. Vide : à parts égales."),
                    stop(".danger-zone", "Supprimer", "Pour une ligne qui n'existe plus. Si elle s'arrête seulement, mettez plutôt une date de fin."));
            case "bien" -> List.of(
                    stop("", "La fiche du bien", "Tout sur ce bien : sa valeur, le marché autour, ses prêts, ses apports et ce qu'il vous a rapporté."),
                    stop(".hero .chips", "Valeur retenue",
                            "Choisissez la valeur à compter : prudente, fourchette, optimiste, prix du marché ou libre. Patria actualise vos estimations avec les ventes du département."),
                    stop("section.metrics", "Les chiffres", "Valeur nette indicative, enrichissement réel, reste à rembourser et prochaine mensualité."),
                    stop("h2|Prix au m²", "Prix au m² et ventes proches",
                            "Votre bien comparé aux vraies ventes autour de chez vous (données publiques) : prix médian, fourchette et ventes comparables."),
                    stop("h2|Prêts", "Prêts du bien", "Ses prêts, et le bouton Mensualités. Ajoutez un prêt en important le tableau d'amortissement de la banque."),
                    stop("h2|Apports", "Apports", "L'argent que vous avez mis vous-même à l'achat."),
                    stop(".heading-actions a[href^='/administration/bien/']", "Modifier le bien",
                            "Adresse, surface, estimations, frais de vente, charges par année et périodes de location ou d'habitation."),
                    stop(pdf, "Exporter en PDF", "La fiche du bien sur une page A4, avec les ventes comparables."));
            case "pret" -> List.of(
                    stop("", "La fiche du prêt", "Où en est ce prêt aujourd'hui, ce qu'il vous coûte, et s'il vaut le coup de le renégocier."),
                    stop("section.metrics", "Les chiffres", "Somme empruntée, reste à rembourser, déjà remboursé, taux actuel et durée restante."),
                    stop("h2|Comparaison avec les taux", "Renégocier ?",
                            "Votre taux comparé à la moyenne des nouveaux prêts. À partir de 0,7 point d'écart, demander une simulation à votre banque peut valoir le coup."),
                    stop("h2|Calendrier", "Le calendrier", "Chaque échéance : capital, intérêts, frais et ce qui reste dû."),
                    stop(".heading-actions a[href$='/gerer']", "Gérer le prêt", "Remplacer le tableau d'amortissement, changer l'assurance, supprimer le prêt."));
            case "economies" -> List.of(
                    stop("", "Vos économies",
                            "Comment payer moins ? Patria reconnaît vos contrats dans vos dépenses et les compare à des prix courants. Rien n'est envoyé à un comparateur."),
                    stop(".level-card", "Votre niveau", "Chaque chose utile faite rapporte des points et fait monter de niveau."),
                    stop("section.metrics", "Déjà économisé, encore à gagner", "Ce que vos renégociations vous font gagner sur un an, et ce qu'il reste à gagner."),
                    stop(".challenge", "Le défi du mois", "La seule chose la plus utile à faire maintenant."),
                    stop("h2|Missions", "Missions", "Des petites actions à faire, avec les points qu'elles rapportent."),
                    stop("h2|Séries", "Séries", "Les mois d'affilée sans déficit, avec un relevé importé ou une épargne en hausse."),
                    stop("details.contract > summary", "Vos contrats",
                            "Ouvrez un contrat : le gain possible par an et la démarche pas à pas. « J'ai renégocié » enregistre le nouveau prix ; le budget le prend le mois suivant."),
                    stop("h2|Objectifs d'épargne", "Objectifs d'épargne", "Vacances, voiture, travaux : ils se remplissent avec le livret choisi. Ajoutez-en par « + Ajouter »."),
                    stop("h2|Badges", "Badges", "Ce que vous avez accompli. Votre niveau s'affiche aussi au survol de votre photo."));
            case "bilan" -> List.of(
                    stop("", "Le Bilan de santé",
                            "Est-ce que mes finances vont bien ? Patria vérifie votre budget, votre épargne, votre immobilier, vos impôts et vos contrats, et explique chaque point en clair."),
                    stop(".health-scope", "Comptes pris en compte", "Par défaut votre compte et les comptes joints que vous voyez. Changez-le ici."),
                    stop(".health-chips", "Le résumé", "Les points à corriger, à surveiller, bons à savoir et en bonne santé. Cliquez pour aller à la liste."),
                    stop(".health-actions", "Corriger", "Chaque point propose un bouton qui mène là où le régler."),
                    stop("form.inline-form button", "Ignorer pour moi", "Retire un point des compteurs, pour vous seul. S'il s'aggrave, il revient."),
                    stop("#ignores", "Points ignorés", "Les points que vous avez ignorés ; « Rétablir » les remet."),
                    stop("#evolution", "Votre évolution", "Une note sur 100, votre pouvoir d'achat, votre patrimoine, votre reste à vivre et le prévu face au réel, mois après mois."),
                    stop(pdf, "Exporter en PDF", "Le bilan sur une page A4, à garder ou imprimer."));
            case "documents" -> List.of(
                    stop("", "Vos documents", "La façon la plus rapide de tout remplir. Choisissez une des trois méthodes ; ce qui est trouvé attend ensuite dans « À trier », à valider étape par étape."),
                    stop("details.import-method > summary|banque", "Avec le fichier de votre banque",
                            "Le plus simple, sans IA. Sur le site de la banque : liste des opérations → Exporter (CSV, OFX ou QIF), sur 3 à 12 mois. "
                                    + "Patria repère les prélèvements et revenus qui reviennent."),
                    stop("details.import-method > summary|IA", "Avec une IA",
                            "Copiez la consigne, donnez-la à une IA avec vos documents, puis collez sa réponse ici. Retirez d'abord ce qui est sensible (IBAN, adresse)."),
                    stop("details.import-method > summary|main", "À la main", "Le guide passe directement aux écrans de saisie."),
                    stop("section.disclosure|Quels documents", "Quels documents ?", "La liste des documents utiles et, banque par banque, où les télécharger."),
                    stop(".wizard-plan > summary", "Plan du guide", "Toutes les étapes du guide, pour sauter directement à l'une d'elles."));
            case "revue-annuelle" -> List.of(
                    stop("", "La revue annuelle", "Une fois par an, mettez tout à jour d'un coup : pour chaque ligne, le nouveau montant et sa date, ou « Arrêtée » si elle n'existe plus."),
                    stop("form.review-row", "Une ligne", "Changez le montant et la date, ou cliquez « Arrêtée ». L'ancien montant reste dans l'historique."),
                    stop("#biens", "Vos biens", "La taxe foncière de l'année et une nouvelle estimation de chaque bien."),
                    stop(".wizard-footer button", "Revue terminée", "Cliquez quand vous avez fini : Patria vous la rappellera dans un an."));
            case "guide" -> List.of(
                    stop("", "Le Guide de saisie",
                            "Il vous fait tout remplir pas à pas, en 15 à 30 minutes. Le plus rapide : déposer le fichier de votre banque (CSV, OFX ou QIF), "
                                    + "ou demander à une IA de lire vos documents. Vous pouvez aussi tout saisir à la main."),
                    stop(".guide-next", "Commencer ou continuer", "Un seul bouton : il vous emmène à l'étape où vous en êtes, avec le temps restant."),
                    stop("h2|Votre parcours", "Votre parcours", "Les étapes qui vous concernent. Une étape laissée vide est notée « à compléter »."),
                    stop("section.disclosure", "Autres actions",
                            "Tout revoir étape par étape, la Revue annuelle (mettre à jour vos montants une fois par an) ou Recommencer à zéro (efface l'avancement, pas vos données)."));
            case "foyer" -> List.of(
                    stop("", "Mon foyer et les droits", "Ici, le gestionnaire décide qui fait partie du budget, qui a un compte et qui voit quoi."),
                    stop(".admin-nav", "Le menu de Gérer", "Toutes les listes et tous les réglages du foyer."),
                    stop("h2|Membres du budget", "Membres du budget",
                            "Les personnes du budget : vous, votre conjoint, un enfant, ou un compte joint. Un membre n'a pas besoin d'un compte pour exister."),
                    stop(".rights-table", "Qui voit quoi",
                            "Pour chaque compte : les membres visibles, les totaux du foyer, l'immobilier et le rôle de gestionnaire. Chaque case permet de voir et de modifier."),
                    stop("h2|Inviter quelqu'un", "Inviter quelqu'un", "Créez un lien d'invitation, valable 7 jours, envoyé par e-mail ou à transmettre vous-même."),
                    stop("section.disclosure|Nom du foyer", "Nom du foyer", "Pour le renommer."));
            case "revenus" -> List.of(
                    stop("", "Vos revenus", "Salaires, pensions, allocations… Ajoutez-les ici, ou importez la réponse d'une IA ou un fichier."),
                    stop(".admin-nav", "Le menu de Gérer", "Revenus, dépenses, virements, biens et prêts, répartition et réglages du foyer."),
                    stop("table.sortable", "La liste",
                            "Cliquez une ligne pour la modifier. Quand un salaire change, « Nouveau montant valable à partir du » garde l'ancien montant avant cette date."),
                    stop("section.disclosure|Importer", "Importer", "Collez la réponse d'une IA ou déposez un fichier : un aperçu s'affiche avant d'enregistrer."));
            case "depenses" -> List.of(
                    stop("", "Vos dépenses", "Loyer, énergie, abonnements, assurances, impôts… Chaque dépense a un montant, une fréquence, une date et qui la paie."),
                    stop(".admin-nav", "Le menu de Gérer", "Revenus, dépenses, virements, biens et prêts, répartition et réglages du foyer."),
                    stop("table.sortable", "La liste",
                            "Cliquez une ligne pour la modifier. Quand un prix change, « Nouveau montant valable à partir du » garde l'ancien montant avant cette date."),
                    stop("section.disclosure|Importer", "Importer", "Collez la réponse d'une IA, ou déposez un fichier CSV (un modèle est fourni) : vous cochez ce que vous gardez."));
            case "virements" -> List.of(
                    stop("", "Les virements entre membres",
                            "Argent de poche, participation au compte joint… L'argent passe d'un membre du budget à un autre, sans être compté deux fois."),
                    stop("table.sortable", "La liste", "Cliquez une ligne pour la modifier."));
            case "repartition" -> List.of(
                    stop("", "La répartition", "La part de chacun dans les biens et les prêts, et qui paie la taxe foncière. Le budget de chacun en tient compte."),
                    stop(".share-list", "Les parts", "Changez une part puis enregistrez la ligne."));
            case "gerer-biens" -> List.of(
                    stop("", "Biens et prêts", "La liste de vos biens et de vos prêts, pour les modifier. Un nouveau bien s'ajoute par « + Ajouter »."),
                    stop("table.sortable", "La liste", "Cliquez une ligne pour modifier le bien ou le prêt, ou remplacer son tableau d'amortissement."));
            case "compte" -> List.of(
                    stop("", "Mon compte", "Tout ce qui vous concerne vous seul : profil, connexion et sécurité."),
                    stop("h2|Profil", "Profil",
                            "Prénom, nom, date de naissance (pour comparer votre mutuelle), téléphone, votre photo et le membre du budget que vous êtes."),
                    stop("h2|Mot de passe", "Mot de passe", "Pour le changer."),
                    stop("h2|E-mail", "E-mail", "Une adresse confirmée permet « Mot de passe oublié » sur la page de connexion."),
                    stop("h2|Double authentification", "Double authentification",
                            "Un code de votre téléphone en plus du mot de passe : la meilleure protection de votre compte."),
                    stop("#connexions", "Vos dernières connexions", "Une connexion inconnue ? Changez de mot de passe et activez la double authentification."),
                    stop("h2|Accès API", "Accès API", "Un jeton en lecture seule pour afficher votre reste à vivre sur un tableau de bord. Ignorez-le si vous n'en avez pas."),
                    stop("form[action='/visites/revoir'] button", "Revoir les visites", "Ce bouton relance toutes les visites guidées, page par page."));
            default -> List.of();
        };
    }
}
