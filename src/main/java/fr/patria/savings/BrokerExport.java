package fr.patria.savings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lecture directe, sans IA, des exports CSV de courtiers et de plateformes crypto (Trade Republic, Revolut, Degiro,
 * Boursorama, Binance, Coinbase…) : les colonnes sont reconnues par leur en-tete, pas par leur position. Un export de
 * transactions est cumule (achats moins ventes, prix de revient au prorata) ; un export de portefeuille est repris
 * tel quel. Le resultat est le format d'import de Patria ({@code code;nom;quantite;prix_de_revient_total}).
 */
final class BrokerExport {
    /** Cryptos les plus courantes : cotees sur Yahoo Finance en euros sous la forme {@code BTC-EUR}. */
    static final Set<String> CRYPTOS = Set.of("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE", "DOT", "LTC", "BNB", "AVAX", "LINK", "POL", "MATIC", "TRX", "XLM",
            "ATOM", "SHIB", "BCH", "XMR", "UNI", "NEAR", "APT", "ARB", "OP", "ETC", "TON", "SUI", "PEPE", "USDT", "USDC");

    private static final List<String> CODE = List.of("isin", "code isin", "symbole/isin", "symbol", "ticker", "asset", "actif", "devise crypto", "coin", "currency pair");
    private static final List<String> NAME = List.of("name", "nom", "produit", "product", "titre", "libelle", "description", "instrument");
    private static final List<String> QUANTITY = List.of("shares", "quantity", "quantite", "qty", "nombre", "anzahl", "amount crypto", "quantity transacted");
    private static final List<String> AMOUNT = List.of("total amount", "montant en eur", "montant", "amount", "total", "value", "valeur", "subtotal", "betrag");
    private static final List<String> FEE = List.of("fee", "fees", "frais", "frais de courtage", "gebuhr");
    private static final List<String> TYPE = List.of("type", "category", "action", "sens", "transaction type", "operation", "typ");
    private static final List<String> SELL = List.of("sell", "vente", "verkauf", "cession");
    private static final List<String> BUY = List.of("buy", "achat", "kauf", "purchase", "savings plan", "sparplan", "plan d'investissement", "reward", "staking", "receive");

    private BrokerExport() {
    }

    /** Code Yahoo Finance d'une crypto connue ({@code BTC} → {@code BTC-EUR}), sinon le code tel quel. */
    static String yahoo(String code) {
        return CRYPTOS.contains(code) ? code + "-EUR" : code;
    }

    /** Lignes au format Patria si le texte est un export reconnu (en-tete avec un code et une quantite), sinon vide. */
    static Optional<String> toLines(String text) {
        String[] all = text.replace("\r\n", "\n").split("\n");
        // Quelques plateformes (Coinbase) placent des lignes d'introduction avant l'en-tete.
        int start = 0;
        while (start < Math.min(all.length, 10) && !isHeader(all[start])) start++;
        if (start >= Math.min(all.length, 10)) return Optional.empty();
        String[] lines = Arrays.copyOfRange(all, start, all.length);
        char separator = separator(lines[0]);
        List<String> header = cells(lines[0], separator).stream().map(BrokerExport::normalize).toList();
        int code = column(header, CODE);
        int quantity = column(header, QUANTITY);
        int name = column(header, NAME);
        int amount = column(header, AMOUNT);
        int fee = column(header, FEE);
        int type = column(header, TYPE);
        Map<String, BigDecimal[]> held = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) continue;
            List<String> row = cells(lines[index], separator);
            String id = pairBase(cell(row, code).toUpperCase(Locale.ROOT).strip());
            BigDecimal shares = number(cell(row, quantity));
            if (id.isEmpty() || shares == null || shares.signum() == 0) continue;
            String kind = normalize(cell(row, type));
            boolean sell;
            if (type < 0 || kind.isEmpty()) sell = shares.signum() < 0;
            else if (SELL.stream().anyMatch(kind::contains)) sell = true;
            else if (BUY.stream().anyMatch(kind::contains)) sell = shares.signum() < 0;
            else continue;
            BigDecimal cost = Optional.ofNullable(number(cell(row, amount))).orElse(BigDecimal.ZERO).abs()
                    .add(sell ? BigDecimal.ZERO : Optional.ofNullable(number(cell(row, fee))).orElse(BigDecimal.ZERO).abs());
            BigDecimal[] position = held.computeIfAbsent(id, key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            names.putIfAbsent(id, name < 0 || cell(row, name).isBlank() ? id : cell(row, name).replace(";", " "));
            if (sell) {
                BigDecimal sold = shares.abs().min(position[0]);
                if (position[0].signum() > 0) position[1] = position[1].subtract(position[1].multiply(sold).divide(position[0], 2, RoundingMode.HALF_UP));
                position[0] = position[0].subtract(sold);
            } else {
                position[0] = position[0].add(shares.abs());
                position[1] = position[1].add(cost);
            }
        }
        List<String> out = new ArrayList<>();
        held.forEach((id, position) -> {
            if (position[0].signum() > 0) out.add(id + ";" + names.get(id) + ";" + position[0].stripTrailingZeros().toPlainString() + ";"
                    + position[1].max(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP).toPlainString());
        });
        return out.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", out));
    }

    private static boolean isHeader(String line) {
        List<String> header = cells(line, separator(line)).stream().map(BrokerExport::normalize).toList();
        return column(header, CODE) >= 0 && column(header, QUANTITY) >= 0;
    }

    /** {@code BTC-EUR}, {@code BTC/EUR}, {@code BTCEUR} → {@code BTC} ; les autres codes (BRK-B, AI.PA) restent tels quels. */
    private static String pairBase(String id) {
        java.util.regex.Matcher pair = java.util.regex.Pattern.compile("([A-Z0-9]+)[-/ ]?(EUR|USD|USDT|USDC)").matcher(id);
        return pair.matches() && CRYPTOS.contains(pair.group(1)) ? pair.group(1) : id;
    }

    private static char separator(String header) {
        long semicolons = header.chars().filter(c -> c == ';').count();
        long tabs = header.chars().filter(c -> c == '\t').count();
        long commas = header.chars().filter(c -> c == ',').count();
        return tabs > semicolons && tabs > commas ? '\t' : semicolons >= commas && semicolons > 0 ? ';' : ',';
    }

    /** Cellules d'une ligne CSV, guillemets compris ({@code "1,5"} reste une seule cellule). */
    static List<String> cells(String line, char separator) {
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

    private static int column(List<String> header, List<String> names) {
        for (String name : names) {
            int index = header.indexOf(name);
            if (index >= 0) return index;
        }
        return -1;
    }

    private static String cell(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index);
    }

    /** Nombre ecrit a la francaise ou a l'anglaise ({@code 1 234,5}, {@code 1,234.5}, {@code USD -12.30}) ; vide si illisible. */
    static BigDecimal number(String raw) {
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

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
