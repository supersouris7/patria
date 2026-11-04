package fr.patria.account;

import java.util.Set;

/**
 * Ce qu'un utilisateur voit dans un foyer.
 *
 * @param wholeHousehold vue « Foyer » (total de tous les membres)
 * @param members        membres dont la vue est ouverte (personnes ou comptes joints)
 * @param realEstate     consultation des biens, prets et charges du foyer
 */
public record Access(long userId, long householdId, String householdName, boolean wholeHousehold, Set<Long> members, boolean realEstate) {
}
