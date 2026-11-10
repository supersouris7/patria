// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecteur JSON minimal pour les reponses des API data.gouv.fr (objets, tableaux, textes, nombres, booleens, null).
 * Objet -> {@code Map<String, Object>}, tableau -> {@code List<Object>}, nombre -> {@link BigDecimal}.
 */
final class Json {
    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.value();
        parser.whitespace();
        if (parser.position != text.length()) throw parser.error("contenu inattendu");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("JSON : objet attendu");
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new IllegalArgumentException("JSON : tableau attendu");
    }

    private Object value() {
        whitespace();
        if (position >= text.length()) throw error("fin inattendue");
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> objectValue();
            case '[' -> arrayValue();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> result = new LinkedHashMap<>();
        position++;
        whitespace();
        if (peek('}')) return result;
        do {
            whitespace();
            String key = string();
            whitespace();
            expect(':');
            result.put(key, value());
            whitespace();
        } while (consume(','));
        expect('}');
        return result;
    }

    private List<Object> arrayValue() {
        List<Object> result = new ArrayList<>();
        position++;
        whitespace();
        if (peek(']')) return result;
        do {
            result.add(value());
            whitespace();
        } while (consume(','));
        expect(']');
        return result;
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return result.toString();
            if (c != '\\') {
                result.append(c);
                continue;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case 'n' -> result.append('\n');
                case 't' -> result.append('\t');
                case 'r' -> result.append('\r');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'u' -> {
                    result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> result.append(escaped);
            }
        }
        throw error("texte non termine");
    }

    private BigDecimal number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
        if (start == position) throw error("valeur inattendue");
        return new BigDecimal(text.substring(start, position));
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) throw error("valeur inattendue");
        position += word.length();
        return value;
    }

    private void whitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
    }

    private boolean peek(char c) {
        if (position < text.length() && text.charAt(position) == c) {
            position++;
            return true;
        }
        return false;
    }

    private boolean consume(char c) {
        return peek(c);
    }

    private void expect(char c) {
        if (!peek(c)) throw error("'" + c + "' attendu");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON invalide (" + message + ", position " + position + ")");
    }
}
