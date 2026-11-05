package fr.patria.common;

/** Ressource absente (bien, pret, apport...) : produit une page 404. */
public final class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String title;

    public NotFoundException(String title, String message) {
        super(message);
        this.title = title;
    }

    public String title() {
        return title;
    }
}
