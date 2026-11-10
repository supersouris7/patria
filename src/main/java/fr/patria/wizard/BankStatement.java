// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import fr.patria.common.CsvText;
import fr.patria.household.Person;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Export d'operations bancaires lu sans IA : OFX (Money), QIF (Quicken) ou CSV (date, libelle, montant ou
 * debit/credit), tels que les proposent les banques (BoursoBank, Banque Populaire, Caisse d'Epargne, LCL, BNP
 * Paribas, N26…). Les operations qui reviennent chaque mois avec un montant stable deviennent des lignes de la pile
 * « A trier » : depenses (prelevements, abonnements) et revenus (salaire, pension). Les depenses variables (courses,
 * carburant) ne sont pas devinees : elles se saisissent en budget moyen.
 */
final class BankStatement {
    /** Une operation : date, libelle, montant (negatif pour une sortie). */
    record Transaction(LocalDate date, String label, BigDecimal amount) {
    }

    private static final List<String> DATE = List.of("date operation", "date de l'operation", "date d'operation", "dateop", "date", "date comptable",
            "booking date", "started date", "completed date", "date de valeur");
    private static final List<String> LABEL = List.of("libelle", "libelle operation", "libelle de l'operation", "label", "description", "detail", "details",
            "payee", "beneficiaire", "nom", "name", "intitule", "libelle simplifie");
    private static final List<String> AMOUNT = List.of("montant", "montant (eur)", "montant(euros)", "montant en euros", "amount", "amount (eur)", "valeur");
    private static final List<String> DEBIT = List.of("debit", "debit euros", "debit (eur)", "montant debit");
    private static final List<String> CREDIT = List.of("credit", "credit euros", "credit (eur)", "montant credit");
    private static final DateTimeFormatter FRENCH = DateTimeFormatter.ofPattern("d/M/uuuu");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d/M/uu");
    private static final DateTimeFormatter DASHED = DateTimeFormatter.ofPattern("d-M-uuuu");
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("uuuuMMdd");

    private BankStatement() {
    }

    /** Operations lues, ou vide si le texte n'est pas un export bancaire reconnu (reponse d'IA, autre fichier). */
    static Optional<List<Transaction>> read(String text) {
        String clean = text.startsWith("﻿") ? text.substring(1) : text;
        if (clean.contains("<STMTTRN>")) return Optional.of(ofx(clean));
        if (clean.startsWith("!Type:") || clean.contains("\n!Type:")) return Optional.of(qif(clean));
        return csv(clean);
    }

    private static List<Transaction> ofx(String text) {
        List<Transaction> found = new ArrayList<>();
        Matcher block = Pattern.compile("<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))", Pattern.DOTALL).matcher(text);
        while (block.find()) {
            String body = block.group(1);
            String date = tag(body, "DTPOSTED");
            BigDecimal amount = date == null ? null : CsvText.number(java.util.Objects.requireNonNullElse(tag(body, "TRNAMT"), ""));
            String label = java.util.Objects.requireNonNullElse(tag(body, "NAME"), java.util.Objects.requireNonNullElse(tag(body, "MEMO"), ""));
            LocalDate day = date == null || date.length() < 8 ? null : parse(date.substring(0, 8));
            if (day != null && amount != null) found.add(new Transaction(day, label, amount));
        }
        return found;
    }

    private static String tag(String body, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">([^<\\r\\n]+)").matcher(body);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static List<Transaction> qif(String text) {
        List<Transaction> found = new ArrayList<>();
        LocalDate date = null;
        BigDecimal amount = null;
        String label = "";
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (line.isEmpty()) continue;
            char code = line.charAt(0);
            String value = line.substring(1).strip();
            switch (code) {
                case 'D' -> date = parse(value.replace('\'', '/'));
                case 'T', 'U' -> amount = CsvText.number(value);
                case 'P' -> label = value;
                case 'M' -> label = label.isEmpty() ? value : label;
                case '^' -> {
                    if (date != null && amount != null) found.add(new Transaction(date, label, amount));
                    date = null;
                    amount = null;
                    label = "";
                }
                default -> {
                }
            }
        }
        return found;
    }

    /** CSV : en-tete reconnu parmi les 10 premieres lignes (quelques banques ajoutent le nom du compte au-dessus). */
    private static Optional<List<Transaction>> csv(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n");
        for (int start = 0; start < Math.min(lines.length, 10); start++) {
            char separator = CsvText.separator(lines[start]);
            List<String> header = CsvText.cells(lines[start], separator).stream().map(CsvText::normalize).toList();
            int date = CsvText.column(header, DATE);
            int label = CsvText.column(header, LABEL);
            int amount = CsvText.column(header, AMOUNT);
            int debit = CsvText.column(header, DEBIT);
            int credit = CsvText.column(header, CREDIT);
            if (date < 0 || label < 0 || amount < 0 && debit < 0 && credit < 0) continue;
            List<Transaction> found = new ArrayList<>();
            for (int index = start + 1; index < lines.length; index++) {
                if (lines[index].isBlank()) continue;
                List<String> row = CsvText.cells(lines[index], separator);
                LocalDate day = parse(CsvText.cell(row, date));
                BigDecimal value;
                if (amount >= 0) {
                    value = CsvText.number(CsvText.cell(row, amount));
                } else {
                    BigDecimal out = CsvText.number(CsvText.cell(row, debit));
                    BigDecimal in = CsvText.number(CsvText.cell(row, credit));
                    value = out != null && out.signum() != 0 ? out.abs().negate() : in;
                }
                if (day != null && value != null) found.add(new Transaction(day, CsvText.cell(row, label), value));
            }
            return Optional.of(found);
        }
        return Optional.empty();
    }

    /** Date au format francais, ISO, compact (OFX) ; null si illisible. */
    static LocalDate parse(String raw) {
        String value = raw.strip();
        if (value.length() >= 10 && value.charAt(4) == '-') value = value.substring(0, 10);
        for (DateTimeFormatter format : List.of(FRENCH, DASHED, COMPACT, SHORT)) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Format suivant.
            }
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * Lignes de la pile pour les operations regulieres : meme libelle (nettoye) au moins deux mois differents, montant
     * stable (±10 % de la mediane). Revenus au nom de {@code person}.
     */
    static List<String> recurring(List<Transaction> transactions, Person person) {
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();
        for (Transaction transaction : transactions) {
            String key = key(transaction.label());
            if (key.isEmpty() || transaction.amount().signum() == 0) continue;
            groups.computeIfAbsent((transaction.amount().signum() < 0 ? "-" : "+") + key, ignored -> new ArrayList<>()).add(transaction);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> group : groups.entrySet()) {
            List<Transaction> items = group.getValue();
            long months = items.stream().map(item -> java.time.YearMonth.from(item.date())).distinct().count();
            if (months < 2) continue;
            List<BigDecimal> amounts = items.stream().map(item -> item.amount().abs()).sorted().toList();
            BigDecimal median = amounts.get(amounts.size() / 2);
            BigDecimal tolerance = median.multiply(new BigDecimal("0.10"));
            if (amounts.stream().anyMatch(amount -> amount.subtract(median).abs().compareTo(tolerance) > 0)) continue;
            // Plusieurs operations dans le meme mois : ce n'est pas un prelevement mensuel (achats repetes).
            if (items.size() > months + 1) continue;
            LocalDate last = items.stream().map(Transaction::date).max(Comparator.naturalOrder()).orElseThrow();
            String label = pretty(group.getKey().substring(1));
            String amount = median.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
            String start = last.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            if (group.getKey().startsWith("-")) {
                lines.add("DEPENSE;" + label + ";" + category(label) + ";" + amount + ";Mensuel;" + start + ";;;Relevé bancaire");
            } else if (person != null) {
                String kind = label.toLowerCase(java.util.Locale.ROOT).matches(".*(salaire|paie|remuneration).*") ? "Salaire net" : "Autre revenu";
                lines.add("REVENU;" + person.name() + ";" + label + ";" + kind + ";" + amount + ";Mensuel;" + start + ";");
            }
        }
        return lines;
    }

    /** Libelle sans le bruit bancaire (PRLV SEPA, CB, dates, references, numeros), pour regrouper les memes operations. */
    static String key(String label) {
        String value = CsvText.normalize(label).toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\b(PRLV|PRELEVEMENT|SEPA|CB|CARTE|PAIEMENT|PAR|VIR|VIREMENT|INST|RECU|EMIS|DE|DU|FACT|FACTURE|ECH|ECHEANCE|REF|MANDAT|ICS|RUM|"
                        + "X\\d+|\\d{2}/\\d{2}(/\\d{2,4})?|\\d{4,})\\b", " ")
                .replaceAll("[^A-Z ]", " ").replaceAll("\\s+", " ").strip();
        String[] words = value.split(" ");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(3, words.length))).strip();
    }

    private static String pretty(String key) {
        StringBuilder result = new StringBuilder();
        for (String word : key.toLowerCase(java.util.Locale.ROOT).split(" ")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    /** Categorie devinee d'apres des mots connus ; « Autres » a defaut (modifiable au tri). */
    static String category(String label) {
        String value = label.toLowerCase(java.util.Locale.ROOT);
        if (value.matches(".*(\\bedf\\b|engie|total ?energies|ekwateur|enercoop|veolia|suez|\\beau\\b|\\bgaz\\b|electricite).*")) return "Eau et énergie";
        if (value.matches(".*(orange|\\bsfr\\b|\\bfree\\b|bouygues|\\bsosh\\b|\\bred\\b|netflix|spotify|deezer|disney|canal|amazon prime|youtube|icloud).*")) {
            return "Télécom et abonnements";
        }
        if (value.matches(".*(mutuelle|harmonie|mgen|alan|malakoff|ameli|pharmacie).*")) return "Santé";
        if (value.matches(".*(assurance|maif|macif|matmut|axa|allianz|groupama|gmf|maaf|mma|generali).*")) return "Assurances";
        if (value.matches(".*(loyer|foncia|nexity|syndic|bailleur).*")) return "Logement";
        if (value.matches(".*(credit|pret|cofidis|cetelem|sofinco|franfinance|oney|leasing|loa).*")) return "Crédits";
        if (value.matches(".*(impot|dgfip|tresor public|finances publiques).*")) return "Impôts et taxes";
        if (value.matches(".*(navigo|sncf|ratp|tcl|transport|peage|vinci|sanef|aprr).*")) return "Transport";
        if (value.matches(".*(creche|cantine|ecole|periscolaire|nounou|garderie).*")) return "Enfants et scolarité";
        if (value.matches(".*(salle|fitness|basic fit|sport|club).*")) return "Loisirs";
        return "Autres";
    }
}
