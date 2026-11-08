package fr.patria.account;

import java.util.Set;

/**
 * Ce qu'un utilisateur voit dans un foyer : une ligne du tableau des droits. Chaque case donne un acces complet (voir
 * et modifier).
 *
 * @param wholeHousehold totaux du foyer et reglages communs (les lignes des membres non coches y sont masquees)
 * @param members        membres accessibles (personnes ou comptes joints)
 * @param realEstate     biens, prets, apports et repartition
 */
public record Access(long userId, long householdId, String householdName, boolean wholeHousehold, Set<Long> members, boolean realEstate) {
    public Access {
        members = members == null ? Set.of() : Set.copyOf(members);
    }

    /** Au moins une case cochee. */
    public boolean any() {
        return wholeHousehold || realEstate || !members.isEmpty();
    }
}
