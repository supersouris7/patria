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
     * Droits dans le foyer affiche.
     *
     * @param wholeHousehold vue « Foyer » (total de tous les membres) ouverte
     * @param members        membres dont la vue est ouverte ; {@code null} pour un gestionnaire (tous)
     * @param realEstate     consultation de tous les biens du foyer
     * @param manages        gestion complete du foyer (utilisateur gestionnaire)
     */
    public record Rights(boolean wholeHousehold, Set<Long> members, boolean realEstate, boolean manages) {
        public static final Rights NONE = new Rights(false, Set.of(), false, false);
        public static final Rights MANAGER = new Rights(true, null, true, true);
    }

    /**
     * Signaux de securite et de transparence.
     *
     * @param mustChangePassword mot de passe choisi par l'administrateur, a changer avant toute autre page
     * @param audience           qui voit quoi dans ce foyer (icone d'information de l'en-tete)
     * @param recent             connexion precedente du compte et echecs depuis (en-tete)
     * @param notice             avis a lire une fois (double authentification retiree par l'administrateur), ou {@code null}
     */
    public record Signals(boolean mustChangePassword, Audience audience, Recent recent, String notice) {
    }

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
        return new Viewer(null, name, household, households, Rights.NONE, new Signals(false, Audience.NONE, recent, null));
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

    public boolean manages() {
        return rights.manages();
    }

    public boolean mustChangePassword() {
        return signals.mustChangePassword();
    }

    public Audience audience() {
        return signals.audience();
    }

    public Recent recent() {
        return signals.recent();
    }

    public String notice() {
        return signals.notice();
    }

    /** L'administrateur de l'application (compte de l'environnement) : foyers, utilisateurs, droits, securite. */
    public boolean isAdministrator() {
        return userId == null;
    }

    /** Vue de ce membre ouverte. */
    public boolean sees(long personId) {
        return members() == null || members().contains(personId);
    }

    /** Tous ces membres ont leur vue ouverte : une ligne qui ne concerne qu'eux est modifiable. */
    public boolean seesAll(Collection<Long> personIds) {
        return members() == null || members().containsAll(personIds);
    }

    /** Une ligne qui concerne au moins un de ces membres est visible. */
    public boolean seesAny(Collection<Long> personIds) {
        return members() == null || personIds.stream().anyMatch(members()::contains);
    }
}
