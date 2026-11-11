// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

/**
 * Situation du foyer donnee au debut du guide par le gestionnaire : elle choisit les etapes proposees.
 * {@code set} : la question a ete repondue ; avant, le parcours courant est propose.
 */
public record Profile(boolean set, boolean couple, boolean joint, boolean tenant, boolean owner, boolean otherProperty, boolean savings) {
    static final Profile UNSET = new Profile(false, false, false, false, false, false, false);
}
