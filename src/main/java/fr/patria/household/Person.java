package fr.patria.household;

/**
 * Membre du foyer : une personne, ou un compte (compte joint) qui recoit des virements et paie les depenses qu'on
 * lui attribue.
 */
public record Person(long id, String name, Kind kind) {
    /** Le code est stocke en base. */
    public enum Kind {
        PERSON("Personne"), ACCOUNT("Compte joint");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Kind parse(String value) {
            for (Kind kind : values()) if (kind.name().equals(value)) return kind;
            throw new fr.patria.common.ValidationException("Type de membre invalide.");
        }
    }

    public Person(long id, String name) {
        this(id, name, Kind.PERSON);
    }

    public boolean isAccount() {
        return kind == Kind.ACCOUNT;
    }
}
