// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.common;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lecture tolerante des fichiers CSV exportes par les banques et courtiers : separateur devine, guillemets, nombres a la francaise ou a l'anglaise. */
public final class CsvText {
    private CsvText() {
    }

    /**
     * Texte d'un fichier exporte : UTF-8, ou Windows-1252 (encodage de nombreux exports bancaires francais) quand les
     * octets ne sont pas de l'UTF-8 valide ; marque d'ordre des octets retiree.
     */
    public static String decode(byte[] bytes) {
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            text = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Separateur le plus present sur la ligne d'en-tete : tabulation, point-virgule ou virgule. */
    public static char separator(String header) {
        long semicolons = header.chars().filter(c -> c == ';').count();
        long tabs = header.chars().filter(c -> c == '\t').count();
        long commas = header.chars().filter(c -> c == ',').count();
        return tabs > semicolons && tabs > commas ? '\t' : semicolons >= commas && semicolons > 0 ? ';' : ',';
    }

    /** Cellules d'une ligne, guillemets compris ({@code "1,5"} reste une seule cellule). */
    public static List<String> cells(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (c == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == separator && !quoted) {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().strip());
        return cells;
    }

    /** Nombre ecrit a la francaise ou a l'anglaise ({@code 1 234,5}, {@code 1,234.5}, {@code USD -12.30}) ; null si illisible. */
    public static BigDecimal number(String raw) {
        String value = raw.replaceAll("[^0-9,.\\-]", "");
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) value = comma > dot ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
        else if (comma >= 0) value = value.replace(',', '.');
        try {
            return value.isEmpty() || value.equals("-") ? null : new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Pour comparer des en-tetes : sans accents, en minuscules, sans espaces autour. */
    public static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Index de la premiere colonne dont l'en-tete (normalise) est l'un de {@code names}, dans l'ordre de preference ; -1 sinon. */
    public static int column(List<String> header, List<String> names) {
        for (String name : names) {
            int index = header.indexOf(name);
            if (index >= 0) return index;
        }
        return -1;
    }

    public static String cell(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index);
    }
}
