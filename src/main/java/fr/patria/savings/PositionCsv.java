package fr.patria.savings;

import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Import des lignes de bourse d'un PEA ou compte-titres (export Trade Republic, Boursorama…, converti par une IA) :
 * {@code code;nom;quantite;prix_de_revient_total}. Le code est un ISIN (converti en code Yahoo Finance) ou deja un
 * code Yahoo (AI.PA).
 */
public final class PositionCsv {
    static final int MAX_LINES = 200;

    /** Une ligne lue, ou son erreur ; {@code duplicate} : deja sur ce compte. */
    public record Row(int line, String code, String symbol, String label, BigDecimal quantity, BigDecimal cost, String error, boolean duplicate) {
        public boolean valid() {
            return error == null;
        }

        public boolean fresh() {
            return error == null && !duplicate;
        }
    }

    private PositionCsv() {
    }

    public static List<Row> parse(String text, List<SavingsRepository.Position> existing, Function<String, Optional<String>> resolve) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.length() > 40_000) throw new ValidationException("Trop de lignes (200 au plus).");
        List<Row> rows = new ArrayList<>();
        List<String> seen = new ArrayList<>(existing.stream().map(position -> position.symbol().toUpperCase(Locale.ROOT)).toList());
        String[] lines = text.replace("\r\n", "\n").split("\n");
        for (int number = 1; number <= lines.length; number++) {
            String line = lines[number - 1].strip();
            if (line.isEmpty()) continue;
            if (rows.size() >= MAX_LINES) throw new ValidationException("Trop de lignes (200 au plus).");
            String[] cells = line.split(";", -1);
            try {
                if (cells.length < 4) throw new ValidationException(cells.length + " colonnes au lieu de 4 : code;nom;quantité;prix de revient total.");
                String code = cells[0].strip().toUpperCase(Locale.ROOT);
                if (rows.isEmpty() && (code.equals("CODE") || code.equals("ISIN"))) throw new ValidationException("Ligne d'en-tête : supprimez-la.");
                if (!code.matches("[A-Z0-9.^=-]{1,20}")) throw new ValidationException("Code invalide : un ISIN (FR0000120073) ou un code Yahoo (AI.PA).");
                String label = cells[1].strip().isEmpty() ? code : cells[1].strip();
                if (label.length() > 60) label = label.substring(0, 60);
                BigDecimal quantity = number(cells[2], "Quantité");
                BigDecimal cost = number(cells[3], "Prix de revient").setScale(2, RoundingMode.HALF_UP);
                String symbol = isIsin(code) ? resolve.apply(code).orElse(code) : code;
                boolean duplicate = seen.contains(symbol);
                seen.add(symbol);
                rows.add(new Row(number, code, symbol, label, quantity, cost, null, duplicate));
            } catch (ValidationException exception) {
                rows.add(new Row(number, cells[0].strip(), null, null, null, null, exception.getMessage(), false));
            }
        }
        if (rows.isEmpty()) throw new ValidationException("Aucune ligne à importer.");
        return rows;
    }

    public static boolean isIsin(String code) {
        return code.matches("[A-Z]{2}[A-Z0-9]{9}\\d");
    }

    private static BigDecimal number(String raw, String label) {
        try {
            BigDecimal value = new BigDecimal(raw.replaceAll("[\\s\\u00a0\\u202f€]", "").replace(',', '.'));
            if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100_000_000)) > 0) throw new NumberFormatException();
            return value.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw new ValidationException(label + " invalide : un nombre positif, par exemple 12,5.");
        }
    }

    /** Prompt pour convertir un export de courtier en lignes a importer. */
    public static String llmPrompt() {
        return """
                Tu m'aides à importer mon portefeuille de bourse (PEA ou compte-titres) dans Patria, une application de budget du foyer. \
                À partir du document joint (export ou relevé de titres de Trade Republic, Boursorama, Fortuneo, Degiro, une banque…), \
                liste les titres que je détiens AUJOURD'HUI.

                Format, à respecter strictement :
                - une ligne par titre, SANS ligne d'en-tête ;
                - 4 colonnes séparées par un point-virgule : code;nom;quantité;prix de revient total
                - code : l'ISIN du titre (12 caractères, ex. FR0000120073, IE00B4L5Y983) ; à défaut le code de cotation ;
                - nom : court (ex. « Air Liquide », « ETF MSCI World ») ;
                - quantité : nombre de parts détenues, virgule décimale autorisée (ex. 3,5) ;
                - prix de revient total : ce que ces parts m'ont coûté au total en euros, frais compris (quantité × prix moyen d'achat), virgule décimale, sans €.

                Exemple :
                IE00B4L5Y983;ETF MSCI World;12,4;1050,30
                FR0000120073;Air Liquide;5;780

                Règles :
                - si le document liste des achats et des ventes (export de transactions), fais le cumul : quantité restante et prix de revient de cette quantité ;
                - ignore les liquidités, les intérêts, les dividendes et les titres entièrement vendus ;
                - n'invente rien : si le prix de revient manque, indique-le après la liste.

                Réponse : les lignes dans un bloc de code, puis la liste courte des points à vérifier.
                """;
    }
}
