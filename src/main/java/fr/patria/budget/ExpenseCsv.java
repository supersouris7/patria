// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Import de depenses par fichier CSV, <b>sans en-tete</b> : chaque ligne est une depense, colonnes dans cet ordre
 * {@code libelle;categorie;montant;frequence;debut;fin} (fin facultative). UTF-8, BOM accepte, separateur
 * point-virgule (ou virgule), guillemets acceptes autour d'un libelle.
 *
 * <p>Categorie et frequence acceptent le libelle affiche (« Santé », « Mensuel ») ou le code ({@code HEALTH},
 * {@code MONTHLY}) ; dates au format 31/01/2026 ou 2026-01-31 ; montants avec virgule ou point. Le fichier ne dit
 * pas a qui revient la depense : cela se choisit dans l'apercu, puis dans chaque depense.
 */
public final class ExpenseCsv {
    static final int MAX_LINES = 500;
    /** L'apercu renvoie le contenu dans un formulaire, limite a 64 Ko une fois encode. */
    static final int MAX_BYTES = 45_000;
    static final int COLUMNS = 6;
    /** Repères proposes a l'IA (7e colonne facultative), pour trier l'apercu par type de detection. */
    public static final List<String> HINTS = List.of("Abonnement", "Télécom", "Streaming", "Énergie", "Assurance", "Santé", "Crédit", "Impôt",
            "Enfants", "Transport", "Ponctuel", "Hausse récente", "Doublon possible", "Résilié", "À vérifier");
    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT);

    /**
     * Une ligne du fichier : la depense lue, ou l'erreur qui l'empeche. {@code duplicate} : deja saisie, elle sera
     * ignoree. {@code hints} : reperes de la 7e colonne (abonnement, doublon possible...), pour choisir quoi importer.
     */
    public record Row(int line, BudgetItem item, String error, boolean duplicate, List<String> hints) {
        public Row(int line, BudgetItem item, String error, boolean duplicate) {
            this(line, item, error, duplicate, List.of());
        }

        /** Coche par defaut dans l'apercu : tout, sauf un doublon possible ou une depense deja resiliee. */
        public boolean suggested() {
            return hints.stream().noneMatch(hint -> hint.equals("Doublon possible") || hint.equals("Résilié"));
        }

        public boolean valid() {
            return error == null;
        }

        /** A enregistrer : valide et pas deja saisie. */
        public boolean fresh() {
            return error == null && !duplicate;
        }
    }

    private ExpenseCsv() {
    }

    /** Lit tout le fichier ; chaque erreur est rapportee sur sa ligne. */
    public static List<Row> parse(String text, List<BudgetItem> existing) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ValidationException("Le fichier est trop gros (45 Ko au plus, soit environ 500 lignes).");
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String firstLine = java.util.Arrays.stream(lines).filter(line -> !line.isBlank()).findFirst()
                .orElseThrow(() -> new ValidationException("Le fichier est vide."));
        char separator = firstLine.indexOf(';') >= 0 ? ';' : ',';
        List<Row> rows = new ArrayList<>();
        List<BudgetItem> seen = new ArrayList<>(existing);
        for (int number = 1; number <= lines.length; number++) {
            String line = lines[number - 1];
            if (line.isBlank() || line.replace(String.valueOf(separator), "").isBlank()) continue;
            if (rows.size() >= MAX_LINES) throw new ValidationException("Le fichier dépasse " + MAX_LINES + " lignes.");
            try {
                List<String> values = split(line, separator);
                if (rows.isEmpty() && looksLikeHeader(values)) {
                    throw new ValidationException("Cette ligne ressemble à un en-tête : supprimez-la, le fichier commence directement par les dépenses.");
                }
                if (values.size() < COLUMNS - 1 || values.size() > COLUMNS + 1) {
                    throw new ValidationException(values.size() + " colonne" + (values.size() > 1 ? "s" : "")
                            + " au lieu de 6 : libellé;catégorie;montant;fréquence;début;fin (et des repères facultatifs en 7e colonne).");
                }
                BudgetItem item = item(values);
                boolean duplicate = seen.stream().anyMatch(other -> same(other, item));
                seen.add(item);
                rows.add(new Row(number, item, null, duplicate, hints(cell(values, COLUMNS))));
            } catch (ValidationException exception) {
                rows.add(new Row(number, null, exception.getMessage(), false));
            }
        }
        return rows;
    }

    /**
     * Prompt a coller dans un assistant IA avec ses documents (releves, avis, contrats) : il renvoie un fichier au
     * format de l'import. Construit a partir des categories et frequences, pour rester a jour.
     */
    public static String llmPrompt() {
        String categories = Category.selectable().stream().map(Category::label).collect(java.util.stream.Collectors.joining(", "));
        String frequencies = java.util.Arrays.stream(Frequency.values()).map(Frequency::label).collect(java.util.stream.Collectors.joining(", "));
        return """
                Tu m'aides à préparer l'import de mes dépenses dans Patria, une application de budget du foyer. Tu travailles en deux temps.

                ÉTAPE 1 — Rassembler les documents (avant tout fichier)
                Regarde ce que je t'ai joint, puis demande-moi, en une seule liste à cocher, ce qui manque parmi :
                - relevés de TOUS les comptes (comptes personnels, compte joint), idéalement 12 mois, au moins 3 ;
                - échéanciers et contrats : électricité, gaz, eau, box internet, forfaits mobiles, assurances (auto, habitation, scolaire, \
                emprunteur), mutuelle, crédits à la consommation, crèche ou garde, cantine, transports ;
                - dernier avis d'impôt sur le revenu et prélèvement à la source ;
                - abonnements payés par carte ou via le téléphone (App Store, Google Play), souvent absents des prélèvements.
                Plus il y a de documents, plus la détection est complète. Attends que je réponde « c'est tout » (ou que j'ajoute des \
                documents) avant l'étape 2.

                ÉTAPE 2 — Détecter le maximum et produire le fichier
                Repère toutes les dépenses qui reviennent (même tous les 2, 3, 6 ou 12 mois), les gros paiements annuels (assurances, \
                impôts), et les paiements ponctuels importants. Je choisirai moi-même quoi importer : propose largement plutôt que d'oublier.

                Format, à respecter strictement :
                - une ligne par dépense, SANS ligne d'en-tête ;
                - 7 colonnes séparées par un point-virgule, dans cet ordre : libellé;catégorie;montant;fréquence;début;fin;repères
                - libellé : court et clair (ex. « Mutuelle », « Électricité EDF »), sans point-virgule ;
                - catégorie : exactement l'une de : %s ;
                - montant : montant d'un seul prélèvement, en euros, positif, virgule décimale, sans symbole € ni espace (ex. 35,69) ;
                - fréquence : exactement l'une de : %s ;
                - début : date du premier prélèvement, au format JJ/MM/AAAA ;
                - fin : date du dernier prélèvement au format JJ/MM/AAAA, ou vide si la dépense continue ;
                - repères : zéro, un ou plusieurs mots parmi %s, séparés par des virgules (ou vide).

                Exemples :
                Mutuelle;Santé;35,69;Mensuel;01/01/2026;;Santé
                Forfait mobile;Télécom et abonnements;19,99;Mensuel;05/01/2026;;Abonnement,Télécom
                Netflix;Télécom et abonnements;13,49;Mensuel;12/01/2026;;Abonnement,Streaming,Doublon possible

                Repères à poser :
                - Doublon possible : deux services qui font la même chose (deux plateformes vidéo, deux box, deux assurances pour le même risque) ;
                - Hausse récente : le montant a augmenté sur la période (indique l'ancien montant dans la liste à vérifier) ;
                - Résilié : le prélèvement s'est arrêté (mets aussi la date de fin) ;
                - Ponctuel : paiement unique ; À vérifier : montant, fréquence ou nature incertains.

                Règles :
                - regroupe les prélèvements qui se répètent en une seule ligne avec la bonne fréquence, pas une ligne par mois ;
                - un paiement unique : fréquence Ponctuel, début = date du paiement, fin vide ;
                - n'inclus pas les virements entre comptes du foyer, l'épargne, les remboursements reçus, ni ce que Patria reprend déjà \
                de l'immobilier : mensualités des prêts immobiliers, taxe foncière, taxe d'habitation, charges de copropriété, assurance des biens ;
                - %d lignes au plus ;
                - n'invente rien : si une information manque ou est incertaine, mets le repère « À vérifier ».

                Confidentialité, à respecter strictement :
                - ne recopie jamais une donnée qui identifie une personne ou un compte : nom, prénom, adresse, e-mail, téléphone, IBAN ou RIB, \
                numéro de compte ou de carte, numéro fiscal, numéro de sécurité sociale, numéro de contrat ou de client, référence de prélèvement (RUM) ;
                - libellés génériques : « Mutuelle », « Électricité », « Crédit auto » ; garde au plus la marque (« EDF », « Netflix »), jamais une référence ;
                - ignore ce qui n'a pas de rapport avec les dépenses (santé, situation familiale, soldes, autres comptes) ;
                - n'écris rien d'autre que la liste de l'étape 1, puis le fichier et la liste des lignes à vérifier : pas de résumé des documents.

                Réponse de l'étape 2 : le contenu CSV dans un bloc de code (ou un fichier .csv en UTF-8 à télécharger), puis, en quelques \
                lignes, ce que tu as détecté par repère (ex. « 3 abonnements télécom, 1 doublon possible ») et les lignes à vérifier.
                """.formatted(categories, frequencies, String.join(", ", HINTS), MAX_LINES);
    }

    /** Reperes de la 7e colonne, ramenes a la liste connue (casse et accents ignores) ; les autres sont gardes tels quels. */
    static List<String> hints(String raw) {
        List<String> result = new ArrayList<>();
        for (String part : raw.split("[,|/]")) {
            String hint = part.trim();
            if (hint.isEmpty() || hint.length() > 40) continue;
            String known = HINTS.stream().filter(candidate -> normalize(candidate).equals(normalize(hint))).findFirst().orElse(hint);
            if (!result.contains(known) && result.size() < 5) result.add(known);
        }
        return List.copyOf(result);
    }

    /** Modele a telecharger : deux exemples, sans en-tete. */
    public static String template() {
        return "\uFEFFMutuelle;Santé;35,69;Mensuel;01/01/2026;\r\nAssurance habitation;Assurances;626,65;Annuel;01/01/2026;\r\n";
    }

    private static boolean looksLikeHeader(List<String> values) {
        return values.size() >= 3 && normalize(values.get(2)).matches("montant|amount");
    }

    private static BudgetItem item(List<String> values) {
        String label = cell(values, 0);
        if (label.isEmpty()) throw new ValidationException("Libellé manquant.");
        if (label.length() > 100) throw new ValidationException("Libellé trop long (100 caractères au plus).");
        fr.patria.common.InputRules.checkText(label, "Libellé");
        Category category = category(cell(values, 1));
        BigDecimal amount = amount(cell(values, 2));
        Frequency frequency = frequency(cell(values, 3));
        LocalDate start = date(cell(values, 4), "Début");
        String rawEnd = cell(values, 5);
        LocalDate end = rawEnd.isEmpty() ? null : date(rawEnd, "Fin");
        if (end != null && end.isBefore(start)) throw new ValidationException("La fin précède le début.");
        return new BudgetItem(0, label, category, amount, frequency, start, end);
    }

    private static boolean same(BudgetItem a, BudgetItem b) {
        return a.label().equalsIgnoreCase(b.label()) && a.amount().compareTo(b.amount()) == 0 && a.frequency() == b.frequency() && a.start().equals(b.start());
    }

    private static Category category(String value) {
        String wanted = normalize(value);
        if (wanted.isEmpty()) throw new ValidationException("Catégorie manquante.");
        for (Category category : Category.selectable()) {
            if (normalize(category.name()).equals(wanted) || normalize(category.label()).equals(wanted)) return category;
        }
        throw new ValidationException("Catégorie « " + value + " » inconnue.");
    }

    private static Frequency frequency(String value) {
        String wanted = normalize(value);
        if (wanted.isEmpty()) throw new ValidationException("Fréquence manquante.");
        for (Frequency frequency : Frequency.values()) {
            if (normalize(frequency.name()).equals(wanted) || normalize(frequency.label()).equals(wanted)) return frequency;
        }
        throw new ValidationException("Fréquence « " + value + " » inconnue (Mensuel, Trimestriel, Semestriel, Annuel ou Ponctuel).");
    }

    private static BigDecimal amount(String value) {
        String cleaned = value.replace("€", "").replace(" ", "").replace(" ", "").replace(" ", "").replace(',', '.');
        if (cleaned.isEmpty()) throw new ValidationException("Montant manquant.");
        BigDecimal amount = fr.patria.common.InputRules.decimal(cleaned);
        if (amount != null && fr.patria.common.InputRules.tooLarge(amount)) {
            throw new ValidationException("Montant « " + value + " » trop élevé (un milliard au plus).");
        }
        if (amount != null && amount.signum() > 0 && amount.scale() <= 2) return amount;
        throw new ValidationException("Montant « " + value + " » invalide (nombre positif, 2 décimales au plus).");
    }

    private static LocalDate date(String value, String label) {
        if (value.isEmpty()) throw new ValidationException(label + " manquant.");
        try {
            return fr.patria.common.InputRules.checkDate(value.contains("/") ? LocalDate.parse(value, FRENCH_DATE) : LocalDate.parse(value), label);
        } catch (DateTimeParseException exception) {
            throw new ValidationException(label + " : date « " + value + " » invalide (31/01/2026 ou 2026-01-31).");
        }
    }

    private static String cell(List<String> values, int index) {
        return index >= values.size() ? "" : values.get(index).trim();
    }

    /** Minuscules, sans accents ni espaces autour : « Santé » et « sante » se valent. */
    static String normalize(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Decoupe une ligne ; un champ entre guillemets peut contenir le separateur ({@code ""} pour un guillemet). */
    static List<String> split(String line, char separator) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' && current.toString().isBlank()) {
                quoted = true;
                current.setLength(0);
            } else if (c == separator) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) throw new ValidationException("Guillemet non refermé.");
        values.add(current.toString());
        return values;
    }
}
