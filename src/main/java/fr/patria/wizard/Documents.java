package fr.patria.wizard;

import fr.patria.budget.Category;
import fr.patria.expense.ExpenseKind;
import fr.patria.household.IncomeKind;
import fr.patria.rent.Occupancy;
import java.util.List;
import java.util.function.Predicate;

/**
 * Documents a rassembler avant de remplir Patria, par theme. Chacun dit a quoi il sert et ou trouver le chiffre utile.
 * {@code auto} : coche verte automatique quand la donnee est deja saisie ; sinon on coche « J'ai ce document » ou
 * « Pas concerné ».
 */
final class Documents {
    record Document(String id, String group, String label, String where, boolean adminOnly, Predicate<Snapshot> auto) {
    }

    private Documents() {
    }

    static final List<Document> ALL = List.of(
            // Revenus
            new Document("payslips", "Revenus", "Les 3 derniers bulletins de salaire de chaque personne",
                    "Ligne « Net à payer avant impôt sur le revenu ». Une prime ou un 13e mois figure sur le bulletin du mois où il est versé.", false,
                    data -> data.hasIncome(IncomeKind.SALARY)),
            new Document("benefits", "Revenus", "Attestations de pension, retraite, allocations (CAF, chômage)",
                    "Montant mensuel versé. Sur l'espace en ligne de l'organisme (caf.fr, France Travail, info-retraite.fr).", false,
                    data -> data.hasIncome(IncomeKind.OTHER)),
            new Document("tax-notice", "Revenus", "Le dernier avis d'impôt sur le revenu",
                    "Sur impots.gouv.fr → Documents. Le taux et le montant du prélèvement à la source sont dans « Votre prélèvement à la source ».", false,
                    data -> data.hasItemNamed("impot sur le revenu", "prelevement a la source")),

            // Banque
            new Document("statements", "Banque", "Les relevés de compte des 3 derniers mois (chaque compte, compte joint compris)",
                    "Ils montrent tous les prélèvements réguliers : l'IA en tire la liste des dépenses à importer (étape Dépenses fixes).",
                    false, data -> false),
            new Document("credits", "Banque", "Les tableaux d'amortissement des crédits à la consommation, auto, travaux",
                    "Montant et date de la dernière mensualité. Espace client de la banque ou de l'organisme de crédit.", false,
                    data -> data.hasItem(Category.CREDIT)),

            // Logement
            new Document("lease", "Logement", "Le bail ou une quittance de loyer (si vous êtes locataire)",
                    "Loyer et charges locatives mensuels.", false, data -> data.hasItemNamed("loyer")),
            new Document("deed", "Logement", "L'acte d'achat ou l'attestation du notaire de chaque bien",
                    "Prix, frais de notaire et date d'acquisition.", true, data -> !data.properties().isEmpty()),
            new Document("amortization", "Logement", "Le tableau d'amortissement de chaque prêt immobilier",
                    "Espace client de la banque ou annexe de l'offre de prêt. L'IA le convertit en échéancier à importer (étape Prêts).", true,
                    data -> !data.loans().isEmpty()),
            new Document("property-tax", "Logement", "Le dernier avis de taxe foncière de chaque bien",
                    "impots.gouv.fr → Gérer mes biens immobiliers, ou l'avis papier d'octobre. Un seul avis suffit : les années suivantes sont estimées.", true,
                    data -> data.hasCharge(ExpenseKind.PROPERTY_TAX)),
            new Document("condo", "Logement", "Les appels de charges de copropriété",
                    "Montant trimestriel, sur l'extranet du syndic.", true, data -> data.hasCharge(ExpenseKind.CONDO_FEES)),
            new Document("home-insurance", "Logement", "Les contrats d'assurance habitation (ou propriétaire non occupant)",
                    "Cotisation annuelle ou mensuelle, sur l'échéancier de l'assureur.", false,
                    data -> data.hasCharge(ExpenseKind.INSURANCE) || data.hasItemNamed("assurance habitation")),
            new Document("rental-lease", "Logement", "Les baux de vos logements loués",
                    "Loyer hors charges perçu chaque mois et date d'entrée du locataire.", true, data -> data.hasRent(Occupancy.RENTAL)),

            // Epargne
            new Document("savings-statements", "Épargne", "Les derniers relevés de vos livrets, assurances-vie et PER",
                    "Solde et date, sur l'espace client de la banque ou de l'assureur.", false, data -> data.hasSavings()),
            new Document("brokerage", "Épargne", "L'export ou le relevé de vos PEA et comptes-titres (Trade Republic, Boursorama, Fortuneo…)",
                    "Trade Republic : Profil → Documents (relevé de titres) ou export des transactions ; ailleurs, le « portefeuille » ou relevé de titres en PDF/CSV. "
                            + "L'IA en tire vos lignes (code ISIN, quantité, prix de revient).", false, data -> data.hasPositions()),

            // Contrats et abonnements
            new Document("energy", "Contrats et abonnements", "Les contrats ou échéanciers d'électricité, de gaz, d'eau",
                    "Mensualité de l'échéancier (ou facture moyenne), sur l'espace client du fournisseur.", false, data -> data.hasItem(Category.ENERGY)),
            new Document("telecom", "Contrats et abonnements", "Les factures de box internet et de forfaits mobiles",
                    "Montant mensuel, sur l'espace client de l'opérateur.", false, data -> data.hasItem(Category.TELECOM)),
            new Document("subscriptions", "Contrats et abonnements", "La liste de vos abonnements (streaming, musique, presse, logiciels, sport)",
                    "Sur les relevés bancaires, ou dans les réglages d'abonnements de votre téléphone (App Store, Google Play).", false,
                    data -> data.hasItemNamed("netflix", "spotify", "disney", "deezer", "canal", "prime", "abonnement")),
            new Document("health", "Contrats et abonnements", "Le contrat de mutuelle et de prévoyance",
                    "Cotisation mensuelle (souvent prélevée sur le salaire : elle est alors déjà déduite du net).", false, data -> data.hasItem(Category.HEALTH)),
            new Document("insurance", "Contrats et abonnements", "Les autres assurances (auto, scolaire, animaux, protection juridique)",
                    "Échéancier de l'assureur.", false, data -> data.hasItem(Category.INSURANCE)),

            // Enfants
            new Document("childcare", "Enfants", "Le contrat de crèche, d'assistante maternelle ou de garde à domicile",
                    "Montant mensuel après aides (CAF, crédit d'impôt) : indiquez ce qui sort réellement du compte.", false, data -> data.hasItem(Category.CHILDREN)),
            new Document("school", "Enfants", "Les factures de cantine, périscolaire, activités",
                    "Moyenne mensuelle sur l'année scolaire.", false, data -> data.hasItemNamed("cantine", "periscolaire")),

            // Deplacements
            new Document("transport", "Déplacements", "L'abonnement de transport, le contrat de leasing ou LOA, la carte de péage",
                    "Montant mensuel ; carburant et péages : une moyenne sur quelques relevés.", false, data -> data.hasItem(Category.TRANSPORT)));

    static List<Document> forViewer(boolean admin) {
        return ALL.stream().filter(document -> admin || !document.adminOnly()).toList();
    }
}
