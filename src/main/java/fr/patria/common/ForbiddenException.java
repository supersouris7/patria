package fr.patria.common;

/** Action ou page reservee (administrateur, ou membre dont la vue n'est pas ouverte) : 403. */
public final class ForbiddenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
