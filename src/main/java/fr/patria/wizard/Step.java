// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Etapes de l'assistant d'import, dans l'ordre. {@code adminOnly} : etapes reservees a l'administrateur (membres,
 * biens, prets, charges, repartition) ; un utilisateur ne voit que celles qui concernent ses propres lignes.
 */
public enum Step {
    SITUATION("situation", "Votre situation", "Quelques clics pour que le guide ne garde que ce qui vous concerne.", false),
    DOCUMENTS("documents", "Vos documents", "Importez vos opérations et vos documents en une fois : avec le fichier de votre banque, avec une IA, ou à la main.", false),
    MEMBERS("membres", "Membres du foyer", "Les personnes du budget, et le compte joint s'il y en a un.", false),
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

    /**
     * Parcours de la personne, dans l'ordre, termine par le recapitulatif. Le gestionnaire commence par sa situation,
     * qui cree les membres et ecarte ce qui ne le concerne pas (logement loue, biens, compte joint, epargne) ; avant
     * qu'il y reponde, le parcours courant est propose. Un autre compte ne saisit que ses propres lignes : import,
     * revenus, depenses fixes, impots, epargne. {@code realEstate} : droit sur l'immobilier. Membres et Bilan de sante
     * restent ouverts hors parcours (les membres se creent dans la situation, le bilan garde son choix par defaut).
     */
    public static List<Step> path(Profile profile, boolean realEstate, boolean manager) {
        if (!manager) return List.of(DOCUMENTS, INCOMES, FIXED, TAXES, SAVINGS, SUMMARY);
        boolean estate = realEstate && profile.set() && (profile.owner() || profile.otherProperty());
        List<Step> steps = new java.util.ArrayList<>(List.of(SITUATION, DOCUMENTS, INCOMES));
        if (!profile.set() || profile.tenant()) steps.add(HOUSING);
        if (estate) steps.addAll(List.of(PROPERTIES, LOANS, CHARGES));
        steps.addAll(List.of(FIXED, TAXES));
        if (!profile.set() || profile.joint()) steps.add(TRANSFERS);
        if (estate && profile.couple()) steps.add(SHARES);
        if (!profile.set() || profile.savings()) steps.add(SAVINGS);
        steps.add(SUMMARY);
        return List.copyOf(steps);
    }

    /** Etape ouverte meme hors parcours (liens depuis d'autres pages). */
    boolean outsidePath() {
        return this == MEMBERS || this == HEALTH;
    }

    /** Minutes estimees pour faire l'etape, pour le temps restant affiche. */
    int minutes() {
        return switch (this) {
            case SITUATION, SHARES, HEALTH, SUMMARY, MEMBERS -> 1;
            case DOCUMENTS, FIXED -> 6;
            case PROPERTIES, LOANS -> 4;
            default -> 2;
        };
    }

    public static Optional<Step> of(String name) {
        return Arrays.stream(values()).filter(step -> step.name().equals(name)).findFirst();
    }
}
