// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.loan;

import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

/**
 * Lecture d'un echeancier CSV (UTF-8, BOM accepte, separateur virgule ou point-virgule, sans guillemets).
 * Format documente dans le README et sur la page d'ajout d'un pret.
 */
public final class CsvScheduleParser {
    static final String HEADER = "date,type,montant,capital_rembourse,interets,frais,capital_restant_du,taux_annuel_pct";
    private static final List<String> COLUMNS = List.of("date", "type", "montant", "capital_rembourse", "interets", "frais", "capital_restant_du", "taux_annuel_pct",
            "pretId", "tauxAnnuelPct", "capitalRembourse", "autres", "capitalRestantDu");
    private static final int MAX_LINES = 10_000;

    private CsvScheduleParser() {
    }

    /** Prompt a coller dans un assistant IA avec le tableau d'amortissement : il renvoie un echeancier au format de l'import. */
    public static String llmPrompt() {
        String types = java.util.Arrays.stream(LineType.values()).map(type -> type.code() + " (" + type.label().toLowerCase() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return """
                Tu prépares l'échéancier d'un prêt à importer dans Patria, une application de suivi de patrimoine. À partir du ou des \
                documents joints (tableau d'amortissement, offre de prêt, relevés du prêt), produis un fichier CSV.

                Format, à respecter strictement :
                - première ligne, cet en-tête exactement : %s
                - puis une ligne par opération, de la plus ancienne à la plus récente ;
                - séparateur virgule, sans guillemets, point décimal, sans symbole € ni espace des milliers (ex. 14500.00) ;
                - date : AAAA-MM-JJ ;
                - type : %s ;
                - montant : total payé ce jour-là, assurance et frais compris (pour un déblocage : somme versée) ;
                - capital_rembourse et interets : part de capital et d'intérêts de l'échéance ;
                - frais : assurance emprunteur et autres frais de l'échéance ;
                - capital_restant_du : capital restant dû après l'opération ;
                - taux_annuel_pct : taux nominal annuel en pourcentage (ex. 3.20) ;
                - une valeur inconnue reste vide, mais chaque ligne a toujours 8 colonnes et au moins un montant.

                Exemple : 2026-01-05,ECHEANCE,850.00,500.00,330.00,20.00,14500.00,3.20

                Règles :
                - recopie toutes les lignes du tableau, échéances futures comprises, jusqu'à la dernière ;
                - n'invente et ne recalcule rien : une valeur absente du document reste vide ; signale après le fichier les lignes incertaines.

                Confidentialité, à respecter strictement :
                - le fichier ne contient que des dates, des types et des montants : ne recopie jamais le nom ou l'adresse de l'emprunteur, \
                le numéro du prêt ou du contrat, l'IBAN, le nom du conseiller ;
                - n'écris rien d'autre que le fichier et la liste des points à vérifier : pas de résumé du document.

                Réponse : le contenu CSV dans un bloc de code (ou un fichier .csv en UTF-8 à télécharger), puis la liste courte des points à vérifier.
                """.formatted(HEADER, types);
    }

    public static List<ScheduleLine> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) throw new ValidationException("Le fichier CSV est vide.");
        char separator = separator(lines[0]);
        List<String> header = columns(lines[0], separator, 1);
        Map<String, Integer> indexes = headerIndexes(header);
        List<ScheduleLine> result = new ArrayList<>();
        for (int lineNumber = 2; lineNumber <= lines.length; lineNumber++) {
            String line = lines[lineNumber - 1];
            if (line.isBlank()) continue;
            if (result.size() >= MAX_LINES) throw new ValidationException("Le CSV contient trop de lignes.");
            List<String> values = columns(line, separator, lineNumber);
            if (values.size() != header.size()) throw new ValidationException("Ligne " + lineNumber + " : nombre de colonnes invalide.");
            LocalDate date = date(value(values, indexes, "date"), lineNumber);
            final int currentLine = lineNumber;
            LineType type = LineType.fromCode(value(values, indexes, "type"))
                    .orElseThrow(() -> new ValidationException("Ligne " + currentLine + " : type invalide."));
            BigDecimal amount = number(value(values, indexes, "montant"), "montant", lineNumber);
            BigDecimal principal = number(value(values, indexes, "capital_rembourse"), "capital_rembourse", lineNumber);
            BigDecimal interest = number(value(values, indexes, "interets"), "interets", lineNumber);
            BigDecimal fees = number(value(values, indexes, "frais"), "frais", lineNumber);
            BigDecimal remaining = number(value(values, indexes, "capital_restant_du"), "capital_restant_du", lineNumber);
            BigDecimal rate = number(value(values, indexes, "taux_annuel_pct"), "taux_annuel_pct", lineNumber);
            if (amount == null && principal == null && interest == null && fees == null && remaining == null) {
                throw new ValidationException("Ligne " + lineNumber + " : au moins une valeur financiere est requise.");
            }
            result.add(new ScheduleLine(date, type, amount, principal, interest, fees, remaining, rate));
        }
        if (result.isEmpty()) throw new ValidationException("Le CSV ne contient aucune echeance.");
        result.sort(Comparator.comparing(ScheduleLine::date));
        return result;
    }

    private static char separator(String header) {
        boolean comma = header.indexOf(',') >= 0;
        boolean semicolon = header.indexOf(';') >= 0;
        if (comma == semicolon) throw new ValidationException("L'en-tete CSV doit utiliser une virgule ou un point-virgule.");
        return comma ? ',' : ';';
    }

    private static Map<String, Integer> headerIndexes(List<String> header) {
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String name = header.get(index).trim();
            if (!COLUMNS.contains(name)) throw new ValidationException("En-tete CSV invalide.");
            if (name.equals("pretId")) continue;
            name = switch (name) {
                case "tauxAnnuelPct" -> "taux_annuel_pct";
                case "capitalRembourse" -> "capital_rembourse";
                case "autres" -> "frais";
                case "capitalRestantDu" -> "capital_restant_du";
                default -> name;
            };
            if (indexes.put(name, index) != null) throw new ValidationException("En-tete CSV invalide.");
        }
        if (!indexes.containsKey("date") || !indexes.containsKey("type")) {
            throw new ValidationException("L'en-tete CSV doit contenir date et type.");
        }
        return indexes;
    }

    private static List<String> columns(String line, char separator, int lineNumber) {
        if (line.indexOf('"') >= 0) throw new ValidationException("Ligne " + lineNumber + " : les guillemets CSV ne sont pas pris en charge.");
        String[] values = line.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        return List.of(values);
    }

    private static String value(List<String> values, Map<String, Integer> indexes, String column) {
        Integer index = indexes.get(column);
        return index == null ? "" : values.get(index);
    }

    private static LocalDate date(String value, int lineNumber) {
        try {
            return fr.patria.common.InputRules.checkDate(LocalDate.parse(value.trim()), "Ligne " + lineNumber);
        } catch (DateTimeParseException exception) {
            throw new ValidationException("Ligne " + lineNumber + " : date invalide.");
        }
    }

    private static BigDecimal number(String value, String column, int lineNumber) {
        value = value.trim().replace(" ", "").replace(',', '.');
        if (value.isEmpty()) return null;
        BigDecimal number = fr.patria.common.InputRules.decimal(value);
        if (number != null && fr.patria.common.InputRules.tooLarge(number)) {
            throw new ValidationException("Ligne " + lineNumber + " : " + column + " trop élevé (un milliard au plus).");
        }
        if (number != null && column.equals("taux_annuel_pct") && number.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ValidationException("Ligne " + lineNumber + " : taux annuel en pourcentage attendu (3.2 pour 3,2 %).");
        }
        if (number != null && number.signum() >= 0) return number;
        throw new ValidationException("Ligne " + lineNumber + " : " + column + " invalide.");
    }
}
