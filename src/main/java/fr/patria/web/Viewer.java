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
 * @param userId             {@code null} pour l'administrateur
 * @param household          foyer affiche
 * @param households         foyers accessibles (selecteur de l'en-tete)
 * @param wholeHousehold     vue « Foyer » (total de tous les membres) ouverte
 * @param members            membres dont la vue est ouverte ; {@code null} pour un gestionnaire (tous)
 * @param realEstate         consultation de l'immobilier du foyer
 * @param manages            gestion complete du foyer affiche (utilisateur gestionnaire de ce foyer)
 * @param mustChangePassword mot de passe choisi par l'administrateur, a changer avant toute autre page
 * @param audience           qui voit quoi dans ce foyer (icone d'information de l'en-tete)
 * @param recent             connexion precedente du compte et echecs depuis (en-tete)
 * @param notice             avis a lire une fois (double authentification retiree par l'administrateur), ou {@code null}
 */
public record Viewer(Long userId, String name, Household household, List<Household> households, boolean wholeHousehold, Set<Long> members,
                     boolean realEstate, boolean manages, boolean mustChangePassword, Audience audience, Recent recent, String notice) {
    /**
     * Derniere connexion avant celle-ci : {@code text} (« hier 21:12 · Lyon, France », ou « première connexion »),
     * {@code failures} : echecs sur ce compte depuis.
     */
    public record Recent(String text, int failures) {
        public static final Recent NONE = new Recent(null, 0);
    }

    /** Un foyer du selecteur ; {@code managed} : l'utilisateur en est gestionnaire. */
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
    public static Viewer admin(String name, Household household, List<Household> households, Recent recent) {
        return new Viewer(null, name, household, households, false, Set.of(), false, false, false, Audience.NONE, recent, null);
    }

    /** Utilisateur gestionnaire du foyer affiche : il y voit et modifie tout. */
    public static Viewer manager(long userId, String name, Household household, List<Household> households, boolean mustChangePassword, Audience audience,
                                 Recent recent, String notice) {
        return new Viewer(userId, name, household, households, true, null, true, true, mustChangePassword, audience, recent, notice);
    }

    /** L'administrateur de l'application (compte de l'environnement) : foyers, utilisateurs, droits, securite. */
    public boolean isAdministrator() {
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
