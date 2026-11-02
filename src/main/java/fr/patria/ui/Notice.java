package fr.patria.ui;

import java.util.Map;

/** Message affiche en haut de page : erreur de saisie ou confirmation d'une action. */
public record Notice(boolean error, String text) {
    /** Confirmations transmises apres redirection par le parametre {@code ?ok=code}. */
    private static final Map<String, String> CONFIRMATIONS = Map.of(
            "cree", "Bien créé.",
            "enregistre", "Modifications enregistrées.",
            "supprime", "Suppression effectuée.",
            "echeancier", "Échéancier importé.",
            "apport", "Apport enregistré.",
            "renomme", "Prêt renommé.");

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
