// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.budget.BudgetItem;
import fr.patria.budget.Category;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chasse aux economies : les contrats du foyer reconnus dans ses depenses (energie, box, mobile, assurances,
 * mutuelle, banque, abonnements), compares a des reperes dates et sources, avec le gain possible par an et la
 * demarche. Reperes indicatifs, revus a chaque version ; rien n'est envoye a un comparateur.
 */
final class Contracts {
    /** Date de verification des reperes, affichee avec eux. */
    static final LocalDate CHECKED = LocalDate.of(2026, 11, 11);

    /** Sorte de contrat : mots reconnus dans le libelle, categories possibles, repere mensuel, demarche. */
    enum Kind {
        ELECTRICITY("Électricité", new String[] {"electricite", "edf", "engie", "totalenergies", "enercoop", "ekwateur", "mint energie", "ohm energie"},
                new Category[] {Category.ENERGY}),
        GAS("Gaz", new String[] {"gaz"}, new Category[] {Category.ENERGY}),
        WATER("Eau", new String[] {"eau", "veolia", "saur", "suez"}, new Category[] {Category.ENERGY}),
        INTERNET("Box internet", new String[] {"box", "internet", "fibre", "adsl", "freebox", "livebox", "bbox"}, new Category[] {Category.TELECOM}),
        MOBILE("Forfait mobile", new String[] {"mobile", "forfait", "portable", "telephone"}, new Category[] {Category.TELECOM}),
        HOME_INSURANCE("Assurance habitation", new String[] {"habitation", "multirisque", "mrh"}, new Category[] {Category.INSURANCE, Category.HOUSING}),
        CAR_INSURANCE("Assurance auto", new String[] {"assurance auto", "assurance voiture", "assurance moto", "auto", "voiture", "vehicule"},
                new Category[] {Category.INSURANCE, Category.TRANSPORT}),
        HEALTH_INSURANCE("Mutuelle", new String[] {"mutuelle", "complementaire sante"}, new Category[] {Category.HEALTH, Category.INSURANCE}),
        BANK("Frais bancaires", new String[] {"frais bancaires", "tenue de compte", "cotisation carte", "carte bancaire", "frais de compte"}, null),
        STREAMING("Abonnements", new String[] {"netflix", "disney", "prime video", "amazon prime", "canal", "spotify", "deezer", "apple", "youtube", "paramount",
                "max", "dazn"}, new Category[] {Category.TELECOM, Category.LEISURE});

        final String label;
        private final String[] words;
        private final Category[] categories;

        Kind(String label, String[] words, Category[] categories) {
            this.label = label;
            this.words = words;
            this.categories = categories;
        }

        boolean matches(BudgetItem item) {
            if (categories != null && java.util.Arrays.stream(categories).noneMatch(category -> category == item.category())) return false;
            String text = " " + normalize(item.label()) + " ";
            // Mots entiers seulement : « auto » ne reconnait pas « autoroute ».
            for (String word : words) if (text.contains(" " + word + " ")) return true;
            return false;
        }
    }

    /** Un contrat reconnu : ses lignes de budget, le cout mensuel, le repere, le gain possible par an et la demarche. */
    record Contract(Kind kind, List<BudgetItem> items, BigDecimal monthly, String benchmark, BigDecimal yearlyGain, String verdict, List<String> steps,
                    String link, String linkLabel) {
        boolean above() {
            return yearlyGain.signum() > 0;
        }
    }

    private Contracts() {
    }

    /** Contrats en cours reconnus parmi les depenses ; {@code age} : age de la personne (mutuelle), {@code null} si inconnu. */
    static List<Contract> find(List<BudgetItem> items, LocalDate today, Integer age) {
        List<Contract> result = new ArrayList<>();
        // Contrats : depenses regulieres en cours (un achat ponctuel n'est pas un contrat).
        List<BudgetItem> current = items.stream().filter(item -> item.frequency().isRecurring() && !item.start().isAfter(today)
                && (item.end() == null || !item.end().isBefore(today))).toList();
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (Kind kind : Kind.values()) {
            List<BudgetItem> found = current.stream().filter(item -> !taken.contains(item.id()) && kind.matches(item)).toList();
            if (found.isEmpty()) continue;
            found.forEach(item -> taken.add(item.id()));
            BigDecimal monthly = found.stream().map(item -> item.frequency().monthly(item.amount())).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            result.add(assess(kind, found, monthly, age));
        }
        return result;
    }

    /** Repere, gain possible et demarche d'un contrat. Gains volontairement prudents : l'ecart a une offre courante. */
    static Contract assess(Kind kind, List<BudgetItem> items, BigDecimal monthly, Integer age) {
        BigDecimal gain = BigDecimal.ZERO;
        String benchmark;
        String verdict;
        List<String> steps;
        String link = null;
        String linkLabel = null;
        switch (kind) {
            case ELECTRICITY -> {
                // Tarif bleu (TRV) au 1er aout 2026, option Base 6 kVA : 0,2001 € le kWh, abonnement 190,32 € par an ; foyer moyen hors chauffage electrique
                // autour de 4 250 kWh par an, soit environ 87 € par mois.
                benchmark = "Environ 87 € par mois pour un foyer moyen sans chauffage électrique (tarif réglementé, option Base 6 kVA, août 2026).";
                verdict = monthly.compareTo(BigDecimal.valueOf(105)) > 0 ? "Au-dessus d'un foyer moyen : normal avec un chauffage électrique, sinon à vérifier."
                        : "Dans les repères.";
                steps = List.of("Comparez les offres sur le comparateur officiel et gratuit du Médiateur national de l'énergie.",
                        "Vérifiez la puissance de votre compteur (kVA) sur votre facture : une puissance trop haute coûte à l'abonnement.",
                        "Changer de fournisseur est gratuit, sans coupure ni frais de résiliation : le nouveau fournisseur s'occupe de tout.");
                link = "https://comparateur.energie-info.fr";
                linkLabel = "Comparateur énergie-info.fr";
            }
            case GAS -> {
                benchmark = "Pas de repère simple : le coût dépend surtout du chauffage et de la surface.";
                verdict = "À comparer une fois par an.";
                steps = List.of("Comparez les offres sur le comparateur officiel et gratuit du Médiateur national de l'énergie.",
                        "Changer de fournisseur est gratuit, sans coupure ni frais de résiliation.");
                link = "https://comparateur.energie-info.fr";
                linkLabel = "Comparateur énergie-info.fr";
            }
            case WATER -> {
                benchmark = "L'eau n'a pas de concurrence : le prix est fixé par votre commune.";
                verdict = "Seule la consommation peut baisser.";
                steps = List.of("Relevez votre compteur le soir puis le matin sans rien utiliser : s'il tourne, une fuite coûte cher.",
                        "Mousseurs et douchettes économes : quelques euros, une consommation nettement plus faible.");
            }
            case INTERNET -> {
                // Arcep, 2e trimestre 2026 : facture moyenne autour de 45 € TTC ; offres fibre d'entree de gamme autour de 27 € (baromètres, aout 2026).
                benchmark = "Facture moyenne autour de 45 € par mois (Arcep) ; offres fibre d'entrée de gamme autour de 27 €.";
                gain = positive(monthly.subtract(BigDecimal.valueOf(30)));
                verdict = gain.signum() > 0 ? "Une offre fibre d'entrée de gamme coûte nettement moins." : "Dans les repères.";
                steps = List.of("Regardez les offres sans engagement de votre opérateur et de ses concurrents : les nouveaux clients paient souvent moins.",
                        "Appelez votre opérateur avec une offre concurrente : il propose souvent un geste pour vous garder.",
                        "Vérifiez la fin de votre engagement avant de partir (frais de résiliation possibles).");
            }
            case MOBILE -> {
                // Arcep, 2e trimestre 2026 : facture moyenne d'un forfait autour de 17 € TTC ; prix moyen a la souscription autour de 13 € (Selectra).
                benchmark = "Facture moyenne autour de 17 € par mois et par ligne (Arcep) ; offres courantes autour de 13 € à la souscription.";
                gain = positive(monthly.subtract(BigDecimal.valueOf(15 * Math.max(1, items.size()))));
                verdict = gain.signum() > 0 ? "Un forfait sans engagement équivalent coûte moins." : "Dans les repères.";
                steps = List.of("Comparez les forfaits sans engagement avec la même quantité de données que vous utilisez vraiment.",
                        "Gardez votre numéro : demandez votre code RIO au 3179 (appel gratuit) et donnez-le au nouvel opérateur, qui fait le reste.");
            }
            case HOME_INSURANCE -> {
                // Cotisations moyennes autour de 200 a 300 € par an selon les sources (comparateurs, France Assureurs).
                benchmark = "Entre 200 et 300 € par an selon les sources (environ 17 à 25 € par mois).";
                gain = positive(monthly.subtract(BigDecimal.valueOf(25))).multiply(new BigDecimal("0.5"));
                verdict = gain.signum() > 0 ? "Au-dessus des repères : un devis concurrent peut faire baisser la cotisation." : "Dans les repères.";
                steps = List.of("Demandez deux ou trois devis avec les mêmes garanties.",
                        "Après un an de contrat, vous pouvez résilier à tout moment (loi Hamon) : le nouvel assureur s'en charge.");
            }
            case CAR_INSURANCE -> {
                benchmark = "Environ 750 € par an en moyenne selon une étude de 2026 (environ 63 € par mois), très variable selon le véhicule et le conducteur.";
                gain = positive(monthly.subtract(BigDecimal.valueOf(75))).multiply(new BigDecimal("0.5"));
                verdict = gain.signum() > 0 ? "Au-dessus des repères : comparez à garanties égales." : "Dans les repères.";
                steps = List.of("Comparez à garanties égales (tous risques ou tiers, franchise, assistance).",
                        "Après un an de contrat, vous pouvez résilier à tout moment (loi Hamon) : le nouvel assureur s'en charge.");
            }
            case HEALTH_INSURANCE -> {
                int reference = mutualReference(age);
                benchmark = age == null ? "Environ 60 € par mois pour une personne de 40 ans ; le prix monte avec l'âge (renseignez votre date de naissance dans Mon compte)."
                        : "Environ " + reference + " € par mois et par personne à " + age + " ans (contrat individuel, moyenne 2026).";
                gain = positive(monthly.subtract(BigDecimal.valueOf(reference * 1.2))).multiply(new BigDecimal("0.5"));
                verdict = gain.signum() > 0 ? "Au-dessus des repères, si ce contrat ne couvre qu'une personne." : "Dans les repères.";
                steps = List.of("Vérifiez d'abord vos vrais besoins (optique, dentaire, hospitalisation) : payer des garanties inutiles coûte cher.",
                        "Après un an de contrat, vous pouvez résilier à tout moment : le nouvel organisme s'en charge.",
                        "Salarié : votre employeur doit vous proposer une mutuelle d'entreprise, souvent moins chère.");
            }
            case BANK -> {
                // Observatoire des tarifs bancaires 2026 : tenue de compte autour de 22 € par an, carte internationale autour de 45 €.
                benchmark = "Tenue de compte autour de 22 € par an et carte autour de 45 € (Observatoire des tarifs bancaires, 2026). Les banques en ligne sont souvent gratuites.";
                gain = monthly.compareTo(BigDecimal.valueOf(2)) > 0 ? monthly : BigDecimal.ZERO;
                verdict = gain.signum() > 0 ? "Ces frais peuvent souvent disparaître." : "Faibles.";
                steps = List.of("Demandez à votre banque de supprimer les frais de tenue de compte, ou comparez avec une banque en ligne.",
                        "Changer de banque est simple : la nouvelle banque transfère prélèvements et virements pour vous (mobilité bancaire).");
            }
            default -> {
                benchmark = items.size() + " abonnement" + (items.size() > 1 ? "s" : "") + " de streaming ou de musique.";
                gain = items.size() >= 3 ? items.stream().map(item -> item.frequency().monthly(item.amount())).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                verdict = items.size() >= 3 ? "Plusieurs abonnements : en suspendre un, puis le reprendre le mois d'une série attendue." : "Raisonnable.";
                steps = List.of("Gardez seulement ce que vous regardez ce mois-ci : la plupart se résilient en deux clics et se reprennent quand vous voulez.",
                        "Regardez les offres groupées de votre box ou de votre opérateur mobile.");
            }
        }
        return new Contract(kind, items, monthly, benchmark, gain.multiply(BigDecimal.valueOf(12)).setScale(0, RoundingMode.HALF_UP), verdict, steps, link, linkLabel);
    }

    /** Cotisation mensuelle moyenne d'une mutuelle individuelle selon l'age (repere 2026). */
    static int mutualReference(Integer age) {
        if (age == null || age < 30) return age == null ? 60 : 33;
        if (age < 50) return 59;
        if (age < 63) return 93;
        if (age < 70) return 104;
        return age < 80 ? 127 : 146;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value.signum() > 0 ? value : BigDecimal.ZERO;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").strip();
    }
}
