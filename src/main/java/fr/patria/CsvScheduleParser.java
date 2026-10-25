package fr.patria;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

final class CsvScheduleParser {
    static final String HEADER = "date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct";
    private static final List<String> COLUMNS = List.of("date", "type", "montant", "capital_rembourse", "interets", "frais", "capital_restant_du", "taux_annuel_pct",
            "pretId", "tauxAnnuelPct", "capitalRembourse", "autres", "capitalRestantDu");
    private static final int MAX_LINES = 10_000;

    private CsvScheduleParser() {
    }

    static List<ScheduleLine> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) throw new IllegalArgumentException("Le fichier CSV est vide.");
        char separator = separator(lines[0]);
        List<String> header = columns(lines[0], separator, 1);
        Map<String, Integer> indexes = headerIndexes(header);
        List<ScheduleLine> result = new ArrayList<>();
        for (int lineNumber = 2; lineNumber <= lines.length; lineNumber++) {
            String line = lines[lineNumber - 1];
            if (line.isBlank()) continue;
            if (result.size() >= MAX_LINES) throw new IllegalArgumentException("Le CSV contient trop de lignes.");
            List<String> values = columns(line, separator, lineNumber);
            if (values.size() != header.size()) throw new IllegalArgumentException("Ligne " + lineNumber + " : nombre de colonnes invalide.");
            LocalDate date = date(value(values, indexes, "date"), lineNumber);
            String type = value(values, indexes, "type").trim().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("DEBLOCAGE", "ECHEANCE", "REMBOURSEMENT_ANTICIPE", "CONSOLIDATION", "AJUSTEMENT").contains(type)) {
                throw new IllegalArgumentException("Ligne " + lineNumber + " : type invalide.");
            }
            BigDecimal amount = number(value(values, indexes, "montant"), "montant", lineNumber);
            BigDecimal principal = number(value(values, indexes, "capital_rembourse"), "capital_rembourse", lineNumber);
            BigDecimal interest = number(value(values, indexes, "interets"), "interets", lineNumber);
            BigDecimal fees = number(value(values, indexes, "frais"), "frais", lineNumber);
            BigDecimal remaining = number(value(values, indexes, "capital_restant_du"), "capital_restant_du", lineNumber);
            BigDecimal rate = number(value(values, indexes, "taux_annuel_pct"), "taux_annuel_pct", lineNumber);
            if (amount == null && principal == null && interest == null && fees == null && remaining == null) {
                throw new IllegalArgumentException("Ligne " + lineNumber + " : au moins une valeur financiere est requise.");
            }
            result.add(new ScheduleLine(date, type, amount, principal, interest, fees, remaining, rate));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Le CSV ne contient aucune echeance.");
        result.sort(Comparator.comparing(ScheduleLine::date));
        return result;
    }

    private static char separator(String header) {
        boolean comma = header.indexOf(',') >= 0;
        boolean semicolon = header.indexOf(';') >= 0;
        if (comma == semicolon) throw new IllegalArgumentException("L'en-tete CSV doit utiliser une virgule ou un point-virgule.");
        return comma ? ',' : ';';
    }

    private static Map<String, Integer> headerIndexes(List<String> header) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index).trim();
            if (!COLUMNS.contains(name)) throw new IllegalArgumentException("En-tete CSV invalide.");
            if (name.equals("pretId")) continue;
            name = switch (name) {
                case "tauxAnnuelPct" -> "taux_annuel_pct";
                case "capitalRembourse" -> "capital_rembourse";
                case "autres" -> "frais";
                case "capitalRestantDu" -> "capital_restant_du";
                default -> name;
            };
            if (indexes.put(name, index) != null) throw new IllegalArgumentException("En-tete CSV invalide.");
        }
        if (!indexes.containsKey("date") || !indexes.containsKey("type")) {
            throw new IllegalArgumentException("L'en-tete CSV doit contenir date et type.");
        }
        return indexes;
    }

    private static List<String> columns(String line, char separator, int lineNumber) {
        if (line.indexOf('"') >= 0) throw new IllegalArgumentException("Ligne " + lineNumber + " : les guillemets CSV ne sont pas pris en charge.");
        String[] values = line.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        return List.of(values);
    }

    private static String value(List<String> values, Map<String, Integer> indexes, String column) {
        Integer index = indexes.get(column);
        return index == null ? "" : values.get(index);
    }

    private static LocalDate date(String value, int lineNumber) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Ligne " + lineNumber + " : date invalide.");
        }
    }

    private static BigDecimal number(String value, String column, int lineNumber) {
        value = value.trim().replace(" ", "").replace(',', '.');
        if (value.isEmpty()) return null;
        try {
            BigDecimal number = new BigDecimal(value);
            if (number.signum() < 0) throw new IllegalArgumentException();
            return number;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Ligne " + lineNumber + " : " + column + " invalide.");
        }
    }
}
