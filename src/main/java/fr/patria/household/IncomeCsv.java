// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.household;

import fr.patria.common.Frequency;
import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Import des revenus (bulletins de salaire, attestations de retraite, CAF, France Travail, convertis par une IA) :
 * {@code personne;libelle;type;montant;frequence;debut;fin}. La personne est un membre du foyer accessible.
 */
public final class IncomeCsv {
    static final int MAX_LINES = 100;
    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    /** Une ligne : le revenu lu (personne resolue) ou son erreur ; {@code duplicate} : deja saisi. */
    public record Row(int line, Income income, String person, String error, boolean duplicate) {
        public boolean valid() {
            return error == null;
        }

        public boolean fresh() {
            return error == null && !duplicate;
        }
    }

    private IncomeCsv() {
    }

    public static List<Row> parse(String text, List<Person> people, List<Income> existing) {
        if (text.startsWith("﻿")) text = text.substring(1);
        if (text.length() > 20_000) throw new ValidationException("Trop de lignes (100 au plus).");
        List<Row> rows = new ArrayList<>();
        List<Income> seen = new ArrayList<>(existing);
        String[] lines = text.replace("\r\n", "\n").split("\n");
        for (int number = 1; number <= lines.length; number++) {
            String line = lines[number - 1].strip();
            if (line.isEmpty()) continue;
            if (rows.size() >= MAX_LINES) throw new ValidationException("Trop de lignes (100 au plus).");
            String[] cells = line.split(";", -1);
            try {
                if (cells.length < 6) throw new ValidationException(cells.length + " colonnes au lieu de 7 : personne;libellé;type;montant;fréquence;début;fin.");
                String name = cells[0].strip();
                if (rows.isEmpty() && normalize(name).equals("personne")) throw new ValidationException("Ligne d'en-tête : supprimez-la.");
                Person person = people.stream().filter(candidate -> normalize(candidate.name()).equals(normalize(name))).findFirst()
                        .orElseThrow(() -> new ValidationException("Membre « " + name + " » inconnu ou non accessible."));
                String label = cells[1].strip().isEmpty() ? "Revenu" : cells[1].strip();
                if (label.length() > 100) throw new ValidationException("Libellé trop long.");
                fr.patria.common.InputRules.checkText(label, "Libellé");
                IncomeKind kind = kind(cells[2]);
                BigDecimal amount = amount(cells[3]);
                Frequency frequency = frequency(cells[4]);
                LocalDate start = date(cells[5].strip(), "Début");
                LocalDate end = cells.length < 7 || cells[6].isBlank() ? null : date(cells[6].strip(), "Fin");
                if (end != null && end.isBefore(start)) throw new ValidationException("La fin précède le début.");
                Income income = new Income(0, person.id(), label, kind, amount, frequency, start, end);
                boolean duplicate = seen.stream().anyMatch(other -> other.personId() == income.personId() && normalize(other.label()).equals(normalize(label))
                        && other.amount().compareTo(amount) == 0 && other.frequency() == frequency && other.start().equals(start));
                seen.add(income);
                rows.add(new Row(number, income, person.name(), null, duplicate));
            } catch (ValidationException exception) {
                rows.add(new Row(number, null, cells[0].strip(), exception.getMessage(), false));
            }
        }
        if (rows.isEmpty()) throw new ValidationException("Aucune ligne à importer.");
        return rows;
    }

    private static IncomeKind kind(String value) {
        String wanted = normalize(value);
        for (IncomeKind kind : IncomeKind.values()) {
            if (normalize(kind.name()).equals(wanted) || normalize(kind.label()).equals(wanted)) return kind;
        }
        if (wanted.startsWith("salaire")) return IncomeKind.SALARY;
        if (wanted.startsWith("prime") || wanted.contains("13e")) return IncomeKind.BONUS;
        if (wanted.isEmpty() || wanted.startsWith("autre") || wanted.contains("retraite") || wanted.contains("pension") || wanted.contains("allocation")) return IncomeKind.OTHER;
        throw new ValidationException("Type « " + value + " » inconnu (Salaire net, Prime, Autre revenu).");
    }

    private static Frequency frequency(String value) {
        String wanted = normalize(value);
        for (Frequency frequency : Frequency.values()) {
            if (normalize(frequency.name()).equals(wanted) || normalize(frequency.label()).equals(wanted)) return frequency;
        }
        throw new ValidationException("Fréquence « " + value + " » inconnue (Mensuel, Trimestriel, Semestriel, Annuel ou Ponctuel).");
    }

    private static BigDecimal amount(String value) {
        BigDecimal amount = fr.patria.common.InputRules.decimal(value.replaceAll("[\\s\\u00a0\\u202f€]", "").replace(',', '.'));
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || fr.patria.common.InputRules.tooLarge(amount)) {
            throw new ValidationException("Montant « " + value + " » invalide (nombre positif, 2 décimales au plus).");
        }
        return amount;
    }

    private static LocalDate date(String value, String label) {
        if (value.isEmpty()) throw new ValidationException(label + " manquant.");
        try {
            return fr.patria.common.InputRules.checkDate(value.contains("/") ? LocalDate.parse(value, FRENCH_DATE) : LocalDate.parse(value), label);
        } catch (DateTimeParseException exception) {
            throw new ValidationException(label + " : date « " + value + " » invalide (31/01/2026 ou 2026-01-31).");
        }
    }

    static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Prompt pour les bulletins de salaire et attestations de revenus. {@code members} : noms des membres a utiliser. */
    public static String llmPrompt(List<String> members) {
        return """
                Tu m'aides à importer les revenus de mon foyer dans Patria, une application de budget. À partir des documents joints (bulletins \
                de salaire, attestations de retraite, de CAF, de France Travail, de pension…), liste les revenus réguliers.

                Format, à respecter strictement :
                - une ligne par revenu, SANS ligne d'en-tête ;
                - 7 colonnes séparées par un point-virgule : personne;libellé;type;montant;fréquence;début;fin
                - personne : exactement l'un de ces prénoms : %s ;
                - libellé : court (ex. « Salaire », « Retraite CNAV », « Allocations familiales ») ;
                - type : exactement l'un de : Salaire net, Prime, Autre revenu ;
                - montant : un seul versement, en euros, virgule décimale, sans € ni espace. Pour un salaire : le « net à payer avant impôt sur \
                le revenu » (avant prélèvement à la source) ;
                - fréquence : exactement l'une de : Mensuel, Trimestriel, Semestriel, Annuel, Ponctuel ;
                - début : date du premier versement au format JJ/MM/AAAA (pour un salaire en cours : le 1er du mois du bulletin le plus ancien) ;
                - fin : vide si le revenu continue.

                Exemple :
                Paul;Salaire;Salaire net;2450,00;Mensuel;01/09/2026;
                Paul;Prime de fin d'année;Prime;1200,00;Annuel;01/12/2026;

                Règles :
                - un 13e mois ou une prime annuelle : une ligne séparée, type Prime, fréquence Annuel ;
                - si le salaire varie d'un bulletin à l'autre, prends le montant habituel (sans les primes exceptionnelles) ;
                - ne recopie jamais nom de famille, adresse, numéro de sécurité sociale, IBAN ou numéro d'employé.

                Réponse : les lignes dans un bloc de code, puis la liste courte des points à vérifier.
                """.formatted(String.join(", ", members));
    }
}
