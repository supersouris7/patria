package fr.patria.account;

import java.util.List;

/** Acces aux bases des foyers, fourni par l'assemblage de l'application (App). */
public interface HouseholdDirectory {
    /** Membre d'un foyer (personne ou compte joint), propose dans les droits d'un utilisateur. */
    record Member(long id, String name, boolean account) {
    }

    List<Member> members(long householdId);

    /** Ajoute un membre (personne ou compte joint) dans un foyer ; renvoie son identifiant. */
    long addMember(long householdId, String name, boolean account);

    /** Supprime un membre et ses lignes propres (refuse s'il porte encore des donnees partagees, selon le foyer). */
    void deleteMember(long householdId, long memberId);

    /**
     * Efface ce qui, dans les bases des foyers, est propre a un utilisateur supprime (progression de l'assistant) :
     * un nouvel utilisateur qui reprendrait son numero repart de zero.
     */
    void forgetUser(long userId);

    /** Base d'un foyer supprime, mise de cote sur le disque ({@code name} : nom du fichier). */
    record Archive(String name, java.time.LocalDateTime deletedOn, long bytes) {
    }

    /** Bases des foyers supprimes, les plus recentes d'abord. */
    List<Archive> archives();

    /** Efface definitivement une base mise de cote (et ses fichiers de journal). */
    void purge(String name);

    /** Avancement du guide de saisie d'un compte dans un foyer : etapes, jamais de donnee saisie. */
    record Progress(int done, int skipped, int total, String next, java.time.LocalDate reviewedOn) {
        public int percent() {
            return total == 0 ? 100 : (done + skipped) * 100 / total;
        }
    }

    Progress progress(long householdId, long userId, boolean realEstate);

    /** La progression de l'administrateur dans ce foyer passe a ce compte (reprise du foyer). */
    void transferFromAdministrator(long householdId, long userId);

    /** Cree et initialise la base d'un nouveau foyer. */
    void created(Household household);

    /** Ferme la base d'un foyer supprime et la met de cote (renommee, jamais effacee). */
    void deleted(Household household);
}
