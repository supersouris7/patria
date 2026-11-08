package fr.patria.health;

/**
 * Un point du bilan de sante.
 *
 * @param link      page ou corriger ou approfondir, {@code null} si aucune
 * @param linkLabel texte du lien
 */
public record Check(String id, Domain domain, Level level, String title, String detail, String link, String linkLabel) {
    /** Domaine du bilan. */
    public enum Domain {
        FINANCES("Finances", "Budget, reste à vivre, endettement, épargne"),
        PROPERTIES("Biens", "Valeur, marché, occupation, prêts"),
        SAVINGS("Épargne", "Précaution, livrets, bourse, pouvoir d'achat"),
        TAXES("Impôts", "Impôt sur le revenu, taxe foncière, revenus fonciers, IFI");

        private final String label;
        private final String summary;

        Domain(String label, String summary) {
            this.label = label;
            this.summary = summary;
        }

        public String label() {
            return label;
        }

        public String summary() {
            return summary;
        }
    }

    /** Voyant : du plus grave au plus rassurant. */
    public enum Level {
        BAD("À corriger", "✕"),
        WARN("À surveiller", "!"),
        INFO("Bon à savoir", "i"),
        GOOD("En bonne santé", "✓");

        private final String label;
        private final String mark;

        Level(String label, String mark) {
            this.label = label;
            this.mark = mark;
        }

        public String label() {
            return label;
        }

        public String mark() {
            return mark;
        }
    }

    static Check of(String id, Domain domain, Level level, String title, String detail) {
        return new Check(id, domain, level, title, detail, null, null);
    }

    Check link(String href, String label) {
        return new Check(id, domain, level, title, detail, href, label);
    }

    /** Une aggravation change le jeton : une alerte devenue plus grave reapparait. */
    String token() {
        return id + ":" + level.name();
    }

    boolean canIgnore() {
        return level != Level.GOOD;
    }
}
