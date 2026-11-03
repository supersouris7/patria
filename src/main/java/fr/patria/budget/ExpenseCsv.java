package fr.patria.budget;

import fr.patria.common.Frequency;
import fr.patria.common.ValidationException;
import fr.patria.household.Person;
import fr.patria.household.Shares;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Import de depenses par fichier CSV (UTF-8, BOM accepte, separateur point-virgule ou virgule, guillemets acceptes).
 *
 * <p>Colonnes : {@code libelle;categorie;montant;frequence;debut;fin}, puis une colonne {@code part_<Prenom>} par
 * personne si besoin. Categorie et frequence acceptent le libelle affiche (« Santé », « Mensuel ») ou le code
 * ({@code HEALTH}, {@code MONTHLY}) ; dates au format 2026-01-31 ou 31/01/2026 ; montants avec virgule ou point.
 * Parts vides : parts egales. Chaque ligne est verifiee comme dans le formulaire.
 */
public final class ExpenseCsv {
    static final int MAX_LINES = 500;
    /** L'apercu renvoie le contenu dans un formulaire, limite a 64 Ko une fois encode. */
    static final int MAX_BYTES = 45_000;
    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("libelle", "libelle"), Map.entry("label", "libelle"), Map.entry("nom", "libelle"),
            Map.entry("categorie", "categorie"), Map.entry("category", "categorie"),
            Map.entry("montant", "montant"), Map.entry("amount", "montant"),
            Map.entry("frequence", "frequence"), Map.entry("rythme", "frequence"), Map.entry("frequency", "frequence"),
            Map.entry("debut", "debut"), Map.entry("premier_paiement", "debut"), Map.entry("start", "debut"),
            Map.entry("fin", "fin"), Map.entry("dernier_paiement", "fin"), Map.entry("end", "fin"));
    private static final List<String> REQUIRED = List.of("libelle", "categorie", "montant", "frequence", "debut");

    /** Une ligne du fichier : la depense lue, ou l'erreur qui l'empeche. {@code duplicate} : deja saisie, elle sera ignoree. */
    public record Row(int line, BudgetItem item, Map<Long, Integer> shares, String error, boolean duplicate) {
        public boolean valid() {
            return error == null;
        }
    }

    private ExpenseCsv() {
    }

    /** Lit tout le fichier ; une erreur d'en-tete arrete la lecture, une erreur de ligne est rapportee sur sa ligne. */
    public static List<Row> parse(String text, List<Person> people, List<BudgetItem> existing) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ValidationException("Le fichier est trop gros (45 Ko au plus, soit environ 500 lignes).");
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) first++;
        if (first == lines.length) throw new ValidationException("Le fichier est vide.");
        char separator = lines[first].indexOf(';') >= 0 ? ';' : ',';
        List<String> header = split(lines[first], separator);
        Map<String, Integer> columns = new HashMap<>();
        Map<Long, Integer> shareColumns = new LinkedHashMap<>();
        for (int index = 0; index < header.size(); index++) {
            String title = header.get(index).trim();
            String name = normalize(title).replace(' ', '_');
            if (name.isEmpty()) continue;
            if (name.startsWith("part_") || name.startsWith("part-")) {
                String who = name.substring(5);
                Person person = people.stream().filter(candidate -> normalize(candidate.name()).replace(' ', '_').equals(who)).findFirst()
                        .orElseThrow(() -> new ValidationException("En-tête : « " + title + " » ne correspond à aucune personne du foyer."));
                shareColumns.put(person.id(), index);
                continue;
            }
            String column = ALIASES.get(name);
            if (column == null) throw new ValidationException("En-tête : colonne « " + title + " » inconnue. Colonnes attendues : "
                    + String.join(";", REQUIRED) + ";fin, puis part_<Prénom> si besoin.");
            if (columns.put(column, index) != null) throw new ValidationException("En-tête : colonne « " + column + " » en double.");
        }
        List<String> missing = REQUIRED.stream().filter(column -> !columns.containsKey(column)).toList();
        if (!missing.isEmpty()) throw new ValidationException("En-tête : colonne(s) manquante(s) : " + String.join(", ", missing) + ".");

        List<Row> rows = new ArrayList<>();
        List<BudgetItem> seen = new ArrayList<>(existing);
        for (int number = first + 2; number <= lines.length; number++) {
            String line = lines[number - 1];
            if (line.isBlank() || line.replace(String.valueOf(separator), "").isBlank()) continue;
            if (rows.size() >= MAX_LINES) throw new ValidationException("Le fichier dépasse " + MAX_LINES + " lignes.");
            try {
                List<String> values = split(line, separator);
                BudgetItem item = item(values, columns);
                Map<String, String> form = new HashMap<>();
                shareColumns.forEach((personId, index) -> form.put("share_" + personId, cell(values, index)));
                Map<Long, Integer> shares = Shares.parse(form, people);
                boolean duplicate = seen.stream().anyMatch(other -> same(other, item));
                seen.add(item);
                rows.add(new Row(number, item, shares, null, duplicate));
            } catch (ValidationException exception) {
                rows.add(new Row(number, null, Map.of(), exception.getMessage(), false));
            }
        }
        if (rows.isEmpty()) throw new ValidationException("Le fichier ne contient aucune dépense sous l'en-tête.");
        return rows;
    }

    public static List<Row> parse(byte[] content, List<Person> people, List<BudgetItem> existing) {
        return parse(new String(content, StandardCharsets.UTF_8), people, existing);
    }

    /** Modele a telecharger : l'en-tete, une colonne de part par personne si le foyer en compte plusieurs, deux exemples. */
    public static String template(List<Person> people) {
        boolean shared = people.size() >= 2;
        StringBuilder csv = new StringBuilder("\uFEFFlibelle;categorie;montant;frequence;debut;fin");
        if (shared) people.forEach(person -> csv.append(";part_").append(person.name()));
        csv.append("\r\n");
        csv.append("Mutuelle;Santé;35,69;Mensuel;01/01/2026;");
        if (shared) people.forEach(person -> csv.append(';'));
        csv.append("\r\n");
        csv.append("Assurance habitation;Assurances;626,65;Annuel;01/01/2026;");
        if (shared) for (int i = 0; i < people.size(); i++) csv.append(';').append(i == 0 ? "100" : "0");
        csv.append("\r\n");
        return csv.toString();
    }

    private static BudgetItem item(List<String> values, Map<String, Integer> columns) {
        String label = cell(values, columns.get("libelle"));
        if (label.isEmpty()) throw new ValidationException("Libellé manquant.");
        if (label.length() > 100) throw new ValidationException("Libellé trop long (100 caractères au plus).");
        Category category = category(cell(values, columns.get("categorie")));
        BigDecimal amount = amount(cell(values, columns.get("montant")));
        Frequency frequency = frequency(cell(values, columns.get("frequence")));
        LocalDate start = date(cell(values, columns.get("debut")), "Début");
        String rawEnd = columns.containsKey("fin") ? cell(values, columns.get("fin")) : "";
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
        for (Category category : Category.values()) {
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
        try {
            BigDecimal amount = new BigDecimal(cleaned);
            if (amount.signum() > 0 && amount.scale() <= 2) return amount;
        } catch (NumberFormatException ignored) {
            // message commun ci-dessous
        }
        throw new ValidationException("Montant « " + value + " » invalide (nombre positif, 2 décimales au plus).");
    }

    private static LocalDate date(String value, String label) {
        if (value.isEmpty()) throw new ValidationException(label + " manquant.");
        try {
            return value.contains("/") ? LocalDate.parse(value, FRENCH_DATE) : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ValidationException(label + " : date « " + value + " » invalide (31/01/2026 ou 2026-01-31).");
        }
    }

    private static String cell(List<String> values, Integer index) {
        return index == null || index >= values.size() ? "" : values.get(index).trim();
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
