// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.savings;

import fr.patria.common.CsvText;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
        char separator = CsvText.separator(lines[0]);
        List<String> header = CsvText.cells(lines[0], separator).stream().map(CsvText::normalize).toList();
        int code = CsvText.column(header, CODE);
        int quantity = CsvText.column(header, QUANTITY);
        int name = CsvText.column(header, NAME);
        int amount = CsvText.column(header, AMOUNT);
        int fee = CsvText.column(header, FEE);
        int type = CsvText.column(header, TYPE);
        Map<String, BigDecimal[]> held = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) continue;
            List<String> row = CsvText.cells(lines[index], separator);
            String id = pairBase(CsvText.cell(row, code).toUpperCase(Locale.ROOT).strip());
            BigDecimal shares = CsvText.number(CsvText.cell(row, quantity));
            if (id.isEmpty() || shares == null || shares.signum() == 0) continue;
            String kind = CsvText.normalize(CsvText.cell(row, type));
            boolean sell;
            if (type < 0 || kind.isEmpty()) sell = shares.signum() < 0;
            else if (SELL.stream().anyMatch(kind::contains)) sell = true;
            else if (BUY.stream().anyMatch(kind::contains)) sell = shares.signum() < 0;
            else continue;
            BigDecimal cost = Optional.ofNullable(CsvText.number(CsvText.cell(row, amount))).orElse(BigDecimal.ZERO).abs()
                    .add(sell ? BigDecimal.ZERO : Optional.ofNullable(CsvText.number(CsvText.cell(row, fee))).orElse(BigDecimal.ZERO).abs());
            BigDecimal[] position = held.computeIfAbsent(id, key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            names.putIfAbsent(id, name < 0 || CsvText.cell(row, name).isBlank() ? id : CsvText.cell(row, name).replace(";", " "));
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

    /**
     * Especes du compte d'apres un releve complet (Trade Republic : versements, achats, ventes, frais, impots,
     * dividendes, tout est dans le meme fichier) : la somme des montants, frais et impots. Vide si le fichier ne
     * contient pas tous les mouvements d'argent.
     */
    static Optional<BigDecimal> cash(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n");
        if (lines.length < 2) return Optional.empty();
        char separator = CsvText.separator(lines[0]);
        List<String> header = CsvText.cells(lines[0].startsWith("\uFEFF") ? lines[0].substring(1) : lines[0], separator).stream().map(CsvText::normalize).toList();
        int amount = header.indexOf("amount");
        if (amount < 0 || !header.contains("transaction_id") || !header.contains("category")) return Optional.empty();
        int fee = header.indexOf("fee");
        int tax = header.indexOf("tax");
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) continue;
            List<String> row = CsvText.cells(lines[index], separator);
            for (int column : new int[]{amount, fee, tax}) {
                BigDecimal value = CsvText.number(CsvText.cell(row, column));
                if (value != null) total = total.add(value);
            }
        }
        return Optional.of(total.setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean isHeader(String line) {
        List<String> header = CsvText.cells(line, CsvText.separator(line)).stream().map(CsvText::normalize).toList();
        return CsvText.column(header, CODE) >= 0 && CsvText.column(header, QUANTITY) >= 0;
    }

    /** {@code BTC-EUR}, {@code BTC/EUR}, {@code BTCEUR} → {@code BTC} ; les autres codes (BRK-B, AI.PA) restent tels quels. */
    private static String pairBase(String id) {
        java.util.regex.Matcher pair = java.util.regex.Pattern.compile("([A-Z0-9]+)[-/ ]?(EUR|USD|USDT|USDC)").matcher(id);
        return pair.matches() && CRYPTOS.contains(pair.group(1)) ? pair.group(1) : id;
    }

}
