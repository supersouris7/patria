package fr.patria.account;

import java.util.List;

/** Acces aux bases des foyers, fourni par l'assemblage de l'application (App). */
public interface HouseholdDirectory {
    /** Membre d'un foyer (personne ou compte joint), propose dans les droits d'un utilisateur. */
    record Member(long id, String name, boolean account) {
    }

    List<Member> members(long householdId);

    /** Cree et initialise la base d'un nouveau foyer. */
    void created(Household household);

    /** Ferme la base d'un foyer supprime et la met de cote (renommee, jamais effacee). */
    void deleted(Household household);
}
