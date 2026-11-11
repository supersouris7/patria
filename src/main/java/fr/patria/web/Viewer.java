// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.web;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Qui consulte la page et ce qu'il a le droit de voir dans le foyer affiche. Relu en base a chaque requete : un
 * changement de droits ou la desactivation d'un compte s'appliquent aussitot.
 *
 * <p>L'administrateur ({@code userId} nul) gere les comptes, les foyers et les droits, mais ne voit aucune donnee des
 * foyers : le routeur ne lui transmet aucune page d'un foyer.
 *
 * @param userId     {@code null} pour l'administrateur
 * @param household  foyer affiche
 * @param households foyers accessibles (selecteur de l'en-tete)
 * @param rights     ce que le compte voit et modifie dans le foyer affiche
 * @param signals    ce que l'en-tete et les pages doivent lui signaler
 */
public record Viewer(Long userId, String name, Household household, List<Household> households, Rights rights, Signals signals) {
    /**
     * Droits dans le foyer affiche, regles par l'administrateur dans le tableau des droits du foyer. Chaque case donne
     * un acces complet (voir et modifier).
     *
     * @param wholeHousehold totaux du foyer et reglages communs (les lignes des membres non coches y sont masquees)
     * @param members        membres accessibles : leurs lignes sont visibles et modifiables
     * @param realEstate     biens, prets, apports et repartition : consultation et modification
     */
    public record Rights(boolean wholeHousehold, Set<Long> members, boolean realEstate, boolean manager) {
        public static final Rights NONE = new Rights(false, Set.of(), false, false);

        public Rights {
            members = members == null ? Set.of() : Set.copyOf(members);
        }

        public static Rights user(boolean wholeHousehold, Set<Long> members, boolean realEstate) {
            return new Rights(wholeHousehold, members, realEstate, false);
        }

        /** {@code manager} : gere le foyer (membres, droits, invitations), sans changer ce qu'il voit. */
        public static Rights user(boolean wholeHousehold, Set<Long> members, boolean realEstate, boolean manager) {
            return new Rights(wholeHousehold, members, realEstate, manager);
        }
    }

    /**
     * Signaux de securite et de transparence.
     *
     * @param mustChangePassword mot de passe choisi par l'administrateur, a changer avant toute autre page
     * @param audience           qui voit quoi dans ce foyer (icone d'information de l'en-tete)
     * @param notice             avis a lire une fois (double authentification retiree par l'administrateur), ou {@code null}
     * @param emailToConfirm     e-mail a confirmer avant toute autre page (exige par l'administrateur a la creation du compte)
     */
    public record Signals(boolean mustChangePassword, Audience audience, String notice, boolean emailToConfirm, boolean avatar) {
        public Signals(boolean mustChangePassword, Audience audience, String notice, boolean emailToConfirm) {
            this(mustChangePassword, audience, notice, emailToConfirm, false);
        }
    }

    /** Un foyer du selecteur ; {@code managed} : l'utilisateur y gere l'immobilier (deplacement d'un bien). */
    public record Household(long id, String name, boolean managed) {
        public Household(long id, String name) {
            this(id, name, false);
        }
    }

    /**
     * Ce que l'icone « qui voit quoi » affiche : une phrase par acces, et les changements d'acces depuis la derniere
     * lecture.
     */
    public record Audience(List<String> lines, List<String> changes) {
        public static final Audience NONE = new Audience(List.of(), List.of());
    }

    /** L'administrateur : aucune vue dans les foyers. */
    public static Viewer admin(String name, Household household, List<Household> households) {
        return new Viewer(null, name, household, households, Rights.NONE, new Signals(false, Audience.NONE, null, false));
    }

    public boolean wholeHousehold() {
        return rights.wholeHousehold();
    }

    public Set<Long> members() {
        return rights.members();
    }

    public boolean realEstate() {
        return rights.realEstate();
    }

    /** Gestionnaire du foyer affiche : membres, droits et invitations. */
    public boolean householdManager() {
        return rights.manager();
    }

    public boolean mustChangePassword() {
        return signals.mustChangePassword();
    }

    public boolean emailToConfirm() {
        return signals.emailToConfirm();
    }

    public Audience audience() {
        return signals.audience();
    }

    public String notice() {
        return signals.notice();
    }

    /** L'administrateur de l'application (compte de l'environnement) : foyers, utilisateurs, droits, securite. */
    public boolean isAdministrator() {
        return userId == null;
    }

    /** Ce membre est accessible. */
    public boolean sees(long personId) {
        return members().contains(personId);
    }

    /** Tous ces membres ont leur vue ouverte : une ligne qui ne concerne qu'eux est modifiable. */
    public boolean seesAll(Collection<Long> personIds) {
        return personIds.stream().allMatch(this::sees);
    }

    /** Une ligne qui concerne au moins un de ces membres est visible. */
    public boolean seesAny(Collection<Long> personIds) {
        return personIds.stream().anyMatch(this::sees);
    }
}
