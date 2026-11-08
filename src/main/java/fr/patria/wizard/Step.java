package fr.patria.wizard;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Etapes de l'assistant d'import, dans l'ordre. {@code adminOnly} : etapes reservees a l'administrateur (membres,
 * biens, prets, charges, repartition) ; un utilisateur ne voit que celles qui concernent ses propres lignes.
 */
public enum Step {
    DOCUMENTS("documents", "Documents à rassembler", "La liste des papiers utiles, à cocher au fur et à mesure.", false),
    MEMBERS("membres", "Membres du foyer", "Les personnes du budget, et le compte joint s'il y en a un.", true),
    INCOMES("revenus", "Revenus", "Salaires, primes, pensions, allocations : ce qui rentre chaque mois.", false),
    HOUSING("logement", "Logement", "Locataire, propriétaire ou les deux : loyer payé et charges du logement.", false),
    PROPERTIES("biens", "Biens immobiliers", "Les logements possédés : résidence principale, location, résidence secondaire.", true),
    LOANS("prets", "Prêts immobiliers", "Le tableau d'amortissement de chaque prêt, importé en un fichier.", true),
    CHARGES("charges", "Charges et loyers des biens", "Taxe foncière, copropriété, assurance, loyers perçus.", true),
    FIXED("depenses", "Dépenses fixes", "Abonnements, énergie, assurances, enfants, déplacements… par catégorie.", false),
    TAXES("impots", "Impôts", "Impôt sur le revenu (prélèvement à la source), solde, autres taxes.", false),
    TRANSFERS("virements", "Virements", "Ce que chacun verse au compte joint, chaque mois.", false),
    SHARES("repartition", "Répartition", "Qui paie quelle part des biens et des prêts.", true),
    SAVINGS("epargne", "Épargne et bourse", "Livrets, assurance-vie, PEA et comptes-titres : soldes, versements programmés et lignes de bourse.", false),
    HEALTH("bilan", "Bilan de santé", "Les comptes que votre bilan prend en compte : le vôtre, les comptes joints, d'autres si besoin.", false),
    SUMMARY("recapitulatif", "Récapitulatif", "Ce qui est fait, passé ou reste à faire.", false);

    private final String slug;
    private final String title;
    private final String summary;
    private final boolean adminOnly;

    Step(String slug, String title, String summary, boolean adminOnly) {
        this.slug = slug;
        this.title = title;
        this.summary = summary;
        this.adminOnly = adminOnly;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public String summary() {
        return summary;
    }

    public boolean adminOnly() {
        return adminOnly;
    }

    public String path() {
        return "/assistant/" + slug;
    }

    /** Etapes proposees a l'administrateur ou a un utilisateur. */
    public static List<Step> forViewer(boolean admin) {
        return Arrays.stream(values()).filter(step -> step != MEMBERS && (admin || !step.adminOnly)).toList();
    }

    public static Optional<Step> of(String name) {
        return Arrays.stream(values()).filter(step -> step.name().equals(name)).findFirst();
    }
}
