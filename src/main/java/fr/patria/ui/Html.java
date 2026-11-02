package fr.patria.ui;

import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Fragment HTML sur. Tout texte insere est echappe par defaut ; seul un {@link Html} est insere tel quel.
 *
 * <pre>{@code
 * Html.of("<a href=\"/bien/%s\">%s</a>", property.id(), property.name())
 * }</pre>
 * Le gabarit utilise uniquement {@code %s}. Un argument {@code Html} est insere brut, {@code null} donne une
 * chaine vide, tout autre objet est converti en texte puis echappe.
 */
public final class Html {
    public static final Html EMPTY = new Html("");
    private final String value;

    private Html(String value) {
        this.value = value;
    }

    /** HTML de confiance ecrit dans le code (jamais une donnee saisie). */
    public static Html raw(String trusted) {
        return new Html(trusted);
    }

    /** Texte quelconque, echappe. */
    public static Html text(Object value) {
        return value instanceof Html html ? html : new Html(escape(value == null ? "" : String.valueOf(value)));
    }

    public static Html of(String template, Object... arguments) {
        StringBuilder result = new StringBuilder(template.length() + 64);
        int argument = 0;
        int from = 0;
        for (int at = template.indexOf("%s"); at >= 0; at = template.indexOf("%s", from)) {
            if (argument >= arguments.length) throw new IllegalArgumentException("Argument manquant pour le gabarit : " + template);
            result.append(template, from, at).append(text(arguments[argument++]).value);
            from = at + 2;
        }
        if (argument != arguments.length) throw new IllegalArgumentException("Trop d'arguments pour le gabarit : " + template);
        return new Html(result.append(template, from, template.length()).toString());
    }

    public static Html when(boolean condition, Html html) {
        return condition ? html : EMPTY;
    }

    public static Html concat(Html... parts) {
        StringBuilder result = new StringBuilder();
        for (Html part : parts) result.append(part.value);
        return new Html(result.toString());
    }

    /** Collecteur pour assembler une liste de fragments : {@code rows.stream().map(...).collect(Html.joining())}. */
    public static Collector<Html, ?, Html> joining() {
        return Collectors.collectingAndThen(Collectors.mapping(html -> html.value, Collectors.joining()), Html::new);
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public String toString() {
        return value;
    }

    static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '"' -> result.append("&quot;");
                case '\'' -> result.append("&#39;");
                default -> result.append(c);
            }
        }
        return result.toString();
    }
}
