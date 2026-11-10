// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import java.util.Set;

/**
 * Ce qu'un utilisateur voit dans un foyer : une ligne du tableau des droits. Chaque case donne un acces complet (voir
 * et modifier).
 *
 * @param wholeHousehold totaux du foyer et reglages communs (les lignes des membres non coches y sont masquees)
 * @param members        membres accessibles (personnes ou comptes joints)
 * @param realEstate     biens, prets, apports et repartition
 * @param manager        gere le foyer : membres, droits de chacun, invitations (ce qu'il voit reste ses propres cases)
 */
public record Access(long userId, long householdId, String householdName, boolean wholeHousehold, Set<Long> members, boolean realEstate, boolean manager) {
    public Access {
        members = members == null ? Set.of() : Set.copyOf(members);
    }

    public Access(long userId, long householdId, String householdName, boolean wholeHousehold, Set<Long> members, boolean realEstate) {
        this(userId, householdId, householdName, wholeHousehold, members, realEstate, false);
    }

    /** Au moins une case cochee. */
    public boolean any() {
        return wholeHousehold || realEstate || !members.isEmpty();
    }
}
