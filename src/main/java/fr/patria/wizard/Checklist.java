// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import fr.patria.budget.Category;
import fr.patria.common.Frequency;
import java.util.List;
import java.util.Optional;

/**
 * Listes a cocher de l'assistant : les depenses regulieres d'un foyer, par theme. Chaque ligne cochee avec un montant
 * devient une depense (libelle, categorie, frequence proposee, modifiable). Les charges des biens (taxe fonciere,
 * copropriete, assurance du bien) et les mensualites des prets immobiliers n'y sont pas : Patria les reprend de
 * l'immobilier.
 */
final class Checklist {
    /**
     * Ligne d'une liste. {@code repeatable} : une ligne qui se saisit souvent par personne (impot a la source, forfaits
     * mobiles, mutuelle…) reste proposee meme deja saisie ; les autres ne sont proposees qu'une fois.
     */
    record Item(String id, String label, Category category, Frequency frequency, String hint, boolean repeatable) {
        Item(String id, String label, Category category, Frequency frequency, String hint) {
            this(id, label, category, frequency, hint, false);
        }

        Item perPerson() {
            return new Item(id, label, category, frequency, hint, true);
        }
    }

    record Group(String id, String title, String explanation, List<Item> items) {
    }

    private Checklist() {
    }

    private static Item monthly(String id, String label, Category category, String hint) {
        return new Item(id, label, category, Frequency.MONTHLY, hint);
    }

    private static Item yearly(String id, String label, Category category, String hint) {
        return new Item(id, label, category, Frequency.ANNUAL, hint);
    }

    /** Etape Logement : ce que paie un locataire. */
    static final Group RENT = new Group("loyer", "Si vous êtes locataire",
            "Le loyer est une dépense fixe : même montant chaque mois. Les charges locatives (provisions sur charges) se saisissent à part si elles "
                    + "figurent séparément sur la quittance.",
            List.of(
                    monthly("loyer", "Loyer", Category.HOUSING, "hors charges, ou charges comprises si elles ne sont pas détaillées"),
                    monthly("charges-locatives", "Charges locatives", Category.HOUSING, "provisions sur charges de la quittance"),
                    monthly("assurance-habitation", "Assurance habitation", Category.INSURANCE, "cotisation mensuelle, ou annuelle en changeant la fréquence"),
                    monthly("parking", "Garage ou parking loué", Category.HOUSING, null)));

    /** Etape Impots. */
    static final Group TAXES = new Group("impots", "Impôts et taxes",
            "Le prélèvement à la source est une dépense mensuelle : son montant figure sur chaque bulletin de salaire (« Impôt sur le revenu prélevé à la source ») "
                    + "et sur impots.gouv.fr. Un solde à payer après la déclaration se saisit en ponctuel ou en mensuel de septembre à décembre. La taxe foncière "
                    + "et la taxe d'habitation d'un bien se saisissent dans les charges du bien, pas ici.",
            List.of(
                    monthly("impot-revenu", "Impôt sur le revenu (prélèvement à la source)", Category.TAXES, "par personne : le montant prélevé chaque mois").perPerson(),
                    new Item("solde-impot", "Solde d'impôt sur le revenu", Category.TAXES, Frequency.ONCE, "montant restant dû sur l'avis (septembre)"),
                    monthly("acompte-revenus-fonciers", "Acomptes sur revenus fonciers ou indépendants", Category.TAXES, "prélevés chaque mois par l'administration"),
                    yearly("cfe", "Cotisation foncière des entreprises (CFE)", Category.TAXES, "auto-entrepreneur : avis de décembre"),
                    yearly("ifi", "Impôt sur la fortune immobilière", Category.TAXES, null)));

    /** Etape Depenses fixes : tous les autres themes. */
    static final List<Group> FIXED = List.of(
            // En premier : ce que l'IA ne voit pas (ou mal) sur les relevés, a passer en revue avec la personne.
            new Group("oublis", "Le tour de ce que l'IA ne voit pas",
                    "Payé en espèces, par chèque, retenu sur le salaire, réglé par l'autre parent ou noyé dans les paiements par carte : ces dépenses "
                            + "manquent souvent dans la réponse de l'IA. Passez-les en revue ; indiquez ce qui sort réellement du compte, après les aides "
                            + "(CAF, complément de libre choix du mode de garde, part de l'employeur), en moyenne mensuelle.",
                    List.of(
                            monthly("creche", "Crèche", Category.CHILDREN, null),
                            monthly("assistante-maternelle", "Assistante maternelle", Category.CHILDREN, "salaire et indemnités, après aides"),
                            monthly("garde-domicile", "Garde à domicile, baby-sitting régulier", Category.CHILDREN, null),
                            monthly("cantine", "Cantine", Category.CHILDREN, "moyenne mensuelle"),
                            monthly("periscolaire", "Périscolaire, centre de loisirs", Category.CHILDREN, null),
                            monthly("pension-alimentaire", "Pension alimentaire versée", Category.CHILDREN, null).perPerson(),
                            monthly("carburant", "Carburant", Category.TRANSPORT, "moyenne sur quelques relevés").perPerson(),
                            monthly("transports-commun", "Abonnement métro, bus, train", Category.TRANSPORT, "Navigo, TCL… après la part de l'employeur").perPerson(),
                            monthly("peages", "Péages", Category.TRANSPORT, null),
                            monthly("stationnement", "Stationnement", Category.TRANSPORT, null),
                            monthly("repas-travail", "Repas au travail", Category.OTHER, "cantine d'entreprise, titres-restaurant").perPerson(),
                            monthly("menage", "Aide à domicile, ménage", Category.OTHER, "après crédit d'impôt"))),
            new Group("energie", "Énergie et eau",
                    "Dépenses fixes si vous êtes mensualisé (échéancier) ; sinon, indiquez la facture moyenne et sa fréquence.",
                    List.of(
                            monthly("electricite", "Électricité", Category.ENERGY, "mensualité de l'échéancier"),
                            monthly("gaz", "Gaz", Category.ENERGY, null),
                            monthly("eau", "Eau", Category.ENERGY, "souvent semestrielle ou comprise dans les charges"),
                            yearly("fioul", "Fioul, bois ou granulés", Category.ENERGY, "achat annuel moyen"),
                            yearly("chaudiere", "Entretien de la chaudière ou de la pompe à chaleur", Category.HOUSING, null),
                            yearly("ramonage", "Ramonage", Category.HOUSING, null))),
            new Group("telecom", "Télécom",
                    "Abonnements à montant fixe chaque mois.",
                    List.of(
                            monthly("box", "Box internet", Category.TELECOM, null),
                            monthly("mobiles", "Forfaits mobiles", Category.TELECOM, "tous les forfaits du foyer, ou un par personne").perPerson(),
                            monthly("fixe", "Téléphone fixe", Category.TELECOM, null))),
            new Group("medias", "Médias et abonnements",
                    "Petits montants, mais réguliers : ils s'additionnent vite. Vérifiez les abonnements de votre téléphone et de votre carte bancaire.",
                    List.of(
                            monthly("netflix", "Netflix", Category.TELECOM, null),
                            monthly("disney", "Disney+", Category.TELECOM, null),
                            monthly("prime", "Amazon Prime", Category.TELECOM, "ou annuel en changeant la fréquence"),
                            monthly("canal", "Canal+", Category.TELECOM, null),
                            monthly("max", "Max", Category.TELECOM, null),
                            monthly("apple-tv", "Apple TV+", Category.TELECOM, null),
                            monthly("spotify", "Spotify", Category.TELECOM, null),
                            monthly("deezer", "Deezer", Category.TELECOM, null),
                            monthly("apple-music", "Apple Music", Category.TELECOM, null),
                            monthly("youtube", "YouTube Premium", Category.TELECOM, null),
                            monthly("presse", "Presse et magazines", Category.TELECOM, null),
                            monthly("cloud", "Stockage en ligne (iCloud, Google One…)", Category.TELECOM, null),
                            yearly("logiciels", "Logiciels (Microsoft 365, antivirus, VPN…)", Category.TELECOM, null),
                            monthly("jeux", "Jeux vidéo en ligne (PlayStation Plus, Game Pass…)", Category.LEISURE, null))),
            new Group("enfants", "Enfants",
                    "Ce qui sort réellement du compte, après les aides. La garde, la cantine et la pension alimentaire sont dans le premier groupe.",
                    List.of(
                            yearly("activites", "Activités extrascolaires (sport, musique…)", Category.CHILDREN, "inscription annuelle").perPerson(),
                            monthly("scolarite", "Scolarité privée, études", Category.CHILDREN, null),
                            monthly("transport-scolaire", "Transport scolaire", Category.CHILDREN, null),
                            monthly("argent-poche", "Argent de poche", Category.CHILDREN, null).perPerson())),
            new Group("deplacements", "Déplacements récurrents",
                    "La voiture et les trajets réguliers. Carburant, transports en commun et péages sont dans le premier groupe.",
                    List.of(
                            monthly("recharge", "Recharge du véhicule électrique", Category.TRANSPORT, null),
                            monthly("leasing", "Leasing ou LOA de la voiture", Category.TRANSPORT, null),
                            monthly("credit-auto", "Crédit auto", Category.CREDIT, null).perPerson(),
                            monthly("assurance-auto", "Assurance auto", Category.INSURANCE, null).perPerson(),
                            yearly("entretien-voiture", "Entretien de la voiture", Category.TRANSPORT, "révision, pneus : moyenne annuelle"),
                            monthly("velo", "Abonnement vélo, trottinette, autopartage", Category.TRANSPORT, null),
                            monthly("train", "Trains ou vols réguliers", Category.TRANSPORT, null))),
            new Group("sante", "Santé et prévoyance",
                    "Si la mutuelle est prélevée sur le salaire, elle est déjà déduite du net : ne la saisissez pas.",
                    List.of(
                            monthly("mutuelle", "Mutuelle", Category.HEALTH, null).perPerson(),
                            monthly("prevoyance", "Prévoyance", Category.HEALTH, null).perPerson(),
                            monthly("soins", "Soins réguliers (orthodontie, kiné…)", Category.HEALTH, "reste à charge"),
                            yearly("optique", "Optique", Category.HEALTH, "reste à charge annuel"),
                            monthly("medicaments", "Médicaments réguliers", Category.HEALTH, null))),
            new Group("assurances", "Autres assurances",
                    "L'assurance habitation se saisit à l'étape Logement, celle d'un bien que vous possédez dans ses charges.",
                    List.of(
                            yearly("assurance-scolaire", "Assurance scolaire", Category.INSURANCE, null),
                            monthly("assurance-moto", "Assurance moto ou scooter", Category.INSURANCE, null).perPerson(),
                            monthly("assurance-animaux", "Assurance animaux", Category.INSURANCE, null),
                            yearly("protection-juridique", "Protection juridique", Category.INSURANCE, null),
                            monthly("gav", "Garantie accidents de la vie", Category.INSURANCE, null),
                            monthly("assurance-vie", "Versements programmés assurance vie, épargne", Category.OTHER, "si vous voulez les voir dans le budget"))),
            new Group("credits", "Crédits et banque",
                    "Les mensualités des prêts immobiliers sont reprises de leur échéancier : ne les saisissez pas ici.",
                    List.of(
                            monthly("credit-conso", "Crédit à la consommation", Category.CREDIT, null).perPerson(),
                            monthly("credit-travaux", "Crédit travaux", Category.CREDIT, null),
                            monthly("credit-renouvelable", "Crédit renouvelable", Category.CREDIT, null),
                            monthly("pret-etudiant", "Prêt étudiant", Category.CREDIT, null),
                            monthly("frais-bancaires", "Frais bancaires", Category.OTHER, null),
                            yearly("carte-bancaire", "Cotisation carte bancaire", Category.OTHER, null))),
            new Group("vie-courante", "Vie courante",
                    "Ces dépenses varient d'un mois à l'autre : indiquez un budget moyen mensuel. Il sert à prévoir ce qui reste à vivre.",
                    List.of(
                            monthly("courses", "Courses alimentaires", Category.OTHER, "budget moyen par mois"),
                            monthly("animaux", "Animaux (nourriture, vétérinaire)", Category.OTHER, null),
                            monthly("sport", "Salle de sport", Category.LEISURE, null).perPerson(),
                            yearly("associations", "Cotisations (club, association, syndicat)", Category.LEISURE, null),
                            monthly("coiffeur", "Coiffeur, soins", Category.OTHER, null),
                            monthly("dons", "Dons réguliers", Category.OTHER, null),
                            monthly("loisirs", "Sorties et loisirs", Category.LEISURE, "budget moyen par mois"),
                            yearly("vacances", "Vacances", Category.LEISURE, "budget annuel"),
                            yearly("cadeaux", "Cadeaux et fêtes", Category.OTHER, "budget annuel"))));

    static List<Item> all() {
        List<Item> items = new java.util.ArrayList<>(RENT.items());
        items.addAll(TAXES.items());
        FIXED.forEach(group -> items.addAll(group.items()));
        return items;
    }

    static Optional<Item> item(String id) {
        return all().stream().filter(item -> item.id().equals(id)).findFirst();
    }
}
