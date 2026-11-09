package fr.patria.wizard;

import java.util.List;
import java.util.Map;

/**
 * Tutoriels « où trouver ce document » par banque, courtier ou organisme, d'apres leurs pages d'aide (verifiees en
 * novembre 2026). Les menus changent : chaque guide finit par le repli commun ({@link #FALLBACK}).
 */
final class DocumentGuides {
    /** Une plateforme et ses etapes, dans l'ordre. */
    record Guide(String name, List<String> steps) {
    }

    static final String CHECKED = "novembre 2026";
    static final String FALLBACK = "Menu introuvable ? Tapez « relevé », « amortissement » ou « attestation » dans la recherche de l'espace client, "
            + "ou demandez le document par la messagerie sécurisée (gratuit en ligne ; un duplicata papier peut être facturé).";

    private DocumentGuides() {
    }

    private static Guide guide(String name, String... steps) {
        return new Guide(name, List.of(steps));
    }

    private static final List<Guide> STATEMENTS = List.of(
            guide("Caisse d'Épargne", "Site : Menu → Documents", "Appli : Plus → Documents → Consulter mes documents", "Filtrez sur le compte et le mois, puis téléchargez le PDF"),
            guide("Banque Populaire", "Espace personnel (en haut à droite) → E-Documents → Consulter mes documents", "Relevés des 10 dernières années, recherche sur 1 an au plus"),
            guide("Crédit Agricole", "Espace client → Mes documents (e-Documents) → Relevés de compte", "Les menus varient selon la caisse régionale : cherchez « relevé »"),
            guide("BNP Paribas / Hello bank!", "Mes Comptes → Comptes et Contrats → choisissez le compte", "Flèche à gauche pour ouvrir le détail → Relevés, ou « Télécharger mes opérations »"),
            guide("Société Générale", "Espace client → e-Documents (relevés et avis en ligne)", "Choisissez le compte et le mois, puis téléchargez le PDF"),
            guide("LCL", "lcl.fr ou appli LCL Mes Comptes → Mes relevés et documents", "Choisissez le relevé, puis téléchargez-le"),
            guide("Crédit Mutuel / CIC", "Appli : Plus → RIB/IBAN et documents → Extraits de comptes et documents", "Site : rubrique documents dématérialisés de l'espace client"),
            guide("La Banque Postale", "Espace client → e-relevés", "Seuls les relevés émis après l'adhésion aux e-relevés sont en ligne"),
            guide("BoursoBank", "Espace client → Mes relevés et documents", "Le relevé du mois arrive au plus tard le 7e jour ouvré du mois suivant"),
            guide("Fortuneo", "Appli : Plus → Documents", "Site : espace client → relevés de compte et de carte"),
            guide("N26", "Appli : Finances → choisissez le compte → roue dentée → Relevés de compte → le mois",
                    "Site : Téléchargements → Activité du compte → dates → PDF ou CSV"),
            guide("Revolut", "Appli : choisissez le compte → … → Relevés → Relevé de compte", "Choisissez la période et le format PDF (CSV aussi possible)"),
            guide("Monabanq et autres", "Espace client ou appli → rubrique « Documents » ou « Relevés »", "Prenez les 3 derniers relevés mensuels en PDF"));

    private static final List<Guide> LOANS = List.of(
            guide("Caisse d'Épargne", "Site : Menu → Documents ; appli : Plus → Documents → Consulter mes documents", "Filtrez sur « Mes crédits » et l'année de signature",
                    "Le tableau d'amortissement est joint à l'offre de prêt"),
            guide("Banque Populaire", "Prêt immobilier : Documents → Mes documents électroniques → Contrats signés → le crédit",
                    "Prêt personnel : Gérer mon crédit → tableau d'amortissement", "Une réédition du tableau se demande aussi en ligne"),
            guide("Crédit Agricole", "Appli : Mes prêts → le crédit → Télécharger le tableau d'amortissement",
                    "Site : Mes comptes → le prêt → onglet Documents ou Échéancier de prêt", "Introuvable : cherchez « amortissement » ou « Réalisation » (e-avis de réalisation)"),
            guide("BNP Paribas / Hello bank!", "Mes Comptes → le crédit → documents du prêt", "Sinon : le conseiller (chat ou messagerie) l'envoie par message sécurisé"),
            guide("Société Générale", "Espace client → le crédit → documents (souvent en ligne 48 h après la signature)", "Sinon : le centre de relation client l'envoie"),
            guide("LCL", "Appli : Mes relevés et documents → Crédits", "Choisissez le fichier (tableau d'amortissement, offre de prêt) et téléchargez-le"),
            guide("Crédit Mutuel / CIC", "Appli : Plus → RIB/IBAN et documents → Autres attestations",
                    "Site : Voir tous les comptes → cliquez sur le solde du prêt → documents"),
            guide("La Banque Postale", "Espace client → documents du prêt, ou l'annexe de l'offre de prêt", "Sinon : le conseiller (une réédition peut être facturée)"),
            guide("BoursoBank", "Crédit immobilier : onglet du crédit → Mes documents",
                    "Prêt personnel : Mes services → Mes documents → Crédits (il se régénère si besoin)",
                    "Après des travaux, le tableau définitif arrive le mois suivant leur fin"),
            guide("Organismes de crédit (Cofidis, Cetelem, Sofinco, Franfinance…)", "Espace client → Mon crédit → Documents ou Échéancier",
                    "Crédit auto en LOA ou leasing : le contrat donne le loyer et la date de fin"));

    private static final List<Guide> BROKERS = List.of(
            guide("Trade Republic", "Appli : Profil → Relevés de compte → Export des transactions (CSV, depuis avril 2026)",
                    "Choisissez « Toutes les transactions » → Créer", "Ou Profil → Documents : le relevé de titres en PDF"),
            guide("Degiro", "Site : Activité → Transactions (trader.degiro.nl/trader/#/transactions)", "Date de début = votre premier achat, puis Exporter en CSV",
                    "Le portefeuille actuel s'exporte aussi depuis la page Portefeuille"),
            guide("BoursoBank", "Espace client → Bourse → le PEA ou compte-titres → Mes documents", "Prenez le relevé de portefeuille (PDF) le plus récent"),
            guide("Fortuneo", "Espace client → Bourse → le portefeuille", "Téléchargez le relevé de portefeuille, ou recopiez les lignes affichées"));

    private static final Map<String, List<Guide>> BY_DOCUMENT = Map.of(
            "payslips", List.of(guide("Employeur", "Coffre-fort numérique de l'employeur (Digiposte, PeopleDoc, MyPeopleDoc…) ou e-mail de paie",
                    "Prenez les 3 derniers bulletins en PDF", "Sinon : demandez-les au service RH")),
            "benefits", List.of(
                    guide("CAF", "caf.fr ou appli Caf - Mon Compte → Mes attestations", "Attestation de paiement → la période → Télécharger"),
                    guide("France Travail", "Espace personnel → Mes échanges et documents → Mes documents → Mes attestations",
                            "Attestation de paiement : vos derniers versements"),
                    guide("Retraite", "info-retraite.fr (FranceConnect) → Mes attestations de paiement (détaillée)",
                            "Complémentaire Agirc-Arrco : agirc-arrco.fr ; pension de base incomplète : lassuranceretraite.fr"),
                    guide("Assurance maladie (ameli)", "Compte ameli → Mes démarches → attestation de paiement d'indemnités journalières", "Jusqu'à 8 ans d'historique")),
            "tax-notice", List.of(guide("impots.gouv.fr", "Votre espace particulier (en haut à droite), numéro fiscal ou FranceConnect",
                    "Menu Documents → Avis d'impôt sur le revenu de l'année → PDF", "Aussi dans l'appli impots.gouv")),
            "property-tax", List.of(guide("impots.gouv.fr", "Votre espace particulier → Documents",
                    "Ligne taxe foncière → icône PDF de la colonne « Avis principal »", "Ou : Gérer mes biens immobiliers")),
            "statements", STATEMENTS,
            "savings-statements", STATEMENTS,
            "credits", LOANS,
            "amortization", LOANS,
            "brokerage", BROKERS);

    /** Noms des plateformes, sans doublon, pour l'autocompletion de la recherche. */
    static List<String> names() {
        return BY_DOCUMENT.values().stream().flatMap(List::stream).map(Guide::name).distinct().sorted().toList();
    }

    /** Pour la recherche : sans accents ni majuscules ni espaces en trop. */
    static String normalize(String value) {
        return java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    static List<Guide> forDocument(String id) {
        return BY_DOCUMENT.getOrDefault(id, List.of());
    }
}
