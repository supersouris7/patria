package fr.patria.web;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Qui consulte la page et ce qu'il a le droit de voir dans le foyer affiche. Relu en base a chaque requete : un
 * changement de droits ou la desactivation d'un compte s'appliquent aussitot.
 *
 * @param userId    {@code null} pour l'administrateur
 * @param household foyer affiche
 * @param households foyers accessibles (selecteur de l'en-tete)
 * @param wholeHousehold vue « Foyer » (total de tous les membres) ouverte
 * @param members   membres dont la vue est ouverte ; {@code null} pour l'administrateur (tous)
 * @param realEstate consultation de l'immobilier du foyer
 */
public record Viewer(Long userId, String name, Household household, List<Household> households, boolean wholeHousehold, Set<Long> members,
                     boolean realEstate) {
    public record Household(long id, String name) {
    }

    public static Viewer admin(String name, Household household, List<Household> households) {
        return new Viewer(null, name, household, households, true, null, true);
    }

    public boolean isAdmin() {
        return userId == null;
    }

    /** Vue de ce membre ouverte. */
    public boolean sees(long personId) {
        return members == null || members.contains(personId);
    }

    /** Tous ces membres ont leur vue ouverte : une ligne qui ne concerne qu'eux est modifiable. */
    public boolean seesAll(Collection<Long> personIds) {
        return members == null || members.containsAll(personIds);
    }

    /** Une ligne qui concerne au moins un de ces membres est visible. */
    public boolean seesAny(Collection<Long> personIds) {
        return members == null || personIds.stream().anyMatch(members::contains);
    }
}
