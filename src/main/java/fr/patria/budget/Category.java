package fr.patria.budget;

import fr.patria.common.ValidationException;
import java.util.Arrays;

/** Categorie d'une depense. Le nom de la constante est stocke en base ; chaque categorie appartient a une famille du graphique. */
public enum Category {
    CREDIT("Crédits", Family.CREDITS),
    HOUSING("Logement", Family.HOUSING),
    TAXES("Impôts et taxes", Family.TAXES),
    ENERGY("Eau et énergie", Family.BILLS),
    TELECOM("Télécom et abonnements", Family.BILLS),
    INSURANCE("Assurances", Family.PROTECTION),
    HEALTH("Santé", Family.PROTECTION),
    TRANSPORT("Transport", Family.OTHER),
    CHILDREN("Enfants et scolarité", Family.OTHER),
    LEISURE("Loisirs", Family.OTHER),
    OTHER("Autres", Family.OTHER),
    /** Virement vers un autre membre du foyer : calcule depuis les virements, jamais saisi comme depense. */
    TRANSFER("Virements", Family.OTHER);

    /**
     * Familles du graphique mensuel : six au plus, pour que chaque couleur reste distinguable (palette validee
     * pour le daltonisme). L'ordre fixe la couleur et l'empilement, du bas vers le haut.
     */
    public enum Family {
        CREDITS("Crédits"),
        HOUSING("Logement"),
        TAXES("Impôts et taxes"),
        BILLS("Factures et abonnements"),
        PROTECTION("Assurances et santé"),
        OTHER("Autres");

        private final String label;

        Family(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String label;
    private final Family family;

    Category(String label, Family family) {
        this.label = label;
        this.family = family;
    }

    public String label() {
        return label;
    }

    public Family family() {
        return family;
    }

    /** Categories proposees a la saisie d'une depense (sans les virements). */
    public static java.util.List<Category> selectable() {
        return Arrays.stream(values()).filter(category -> category != TRANSFER).toList();
    }

    public static Category parse(String value) {
        return selectable().stream().filter(category -> category.name().equals(value)).findFirst()
                .orElseThrow(() -> new ValidationException("Catégorie invalide."));
    }
}
