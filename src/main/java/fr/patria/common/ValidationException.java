package fr.patria.common;

/** Saisie invalide : le message est destine a l'utilisateur et affiche tel quel. */
public final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
