// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.ui;

import java.util.Map;

/** Message affiche en haut de page : erreur de saisie ou confirmation d'une action. */
public record Notice(boolean error, String text) {
    /** Confirmations transmises apres redirection par le parametre {@code ?ok=code}. */
    private static final Map<String, String> CONFIRMATIONS = Map.ofEntries(
            Map.entry("cree", "Bien créé."),
            Map.entry("enregistre", "Modifications enregistrées."),
            Map.entry("supprime", "Suppression effectuée."),
            Map.entry("echeancier", "Échéancier importé."),
            Map.entry("apport", "Apport enregistré."),
            Map.entry("renomme", "Prêt renommé."),
            Map.entry("charge", "Charge enregistrée."),
            Map.entry("loyer", "Période d'occupation enregistrée."),
            Map.entry("importe", "Dépenses importées."),
            Map.entry("foyer", "Foyer créé."),
            Map.entry("utilisateur", "Utilisateur créé. Vérifiez ci-dessous ses foyers et ce qu'il voit."),
            Map.entry("acces", "Droits enregistrés."),
            Map.entry("mot-de-passe", "Mot de passe modifié."),
            Map.entry("mot-de-passe-provisoire", "Mot de passe réinitialisé : provisoire, l'utilisateur le changera à sa prochaine connexion."),
            Map.entry("double-authentification", "Double authentification retirée. L'utilisateur en sera averti à sa prochaine connexion."),
            Map.entry("double-authentification-active", "Double authentification activée : le code sera demandé à chaque connexion."),
            Map.entry("double-authentification-inactive", "Double authentification désactivée."),
            Map.entry("reprise", "Compte créé : il gère maintenant ces foyers. Connectez-vous avec lui pour retrouver vos données."),
            Map.entry("deplace", "Bien déplacé : il est maintenant dans ce foyer, avec ses prêts et ses charges."));

    public static Notice error(String text) {
        return new Notice(true, text);
    }

    public static Notice success(String text) {
        return new Notice(false, text);
    }

    /** Confirmation correspondant a un code {@code ?ok=}, ou {@code null}. */
    public static Notice confirmation(String code) {
        String text = code == null ? null : CONFIRMATIONS.get(code);
        return text == null ? null : success(text);
    }
}
