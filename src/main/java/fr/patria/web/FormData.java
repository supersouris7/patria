package fr.patria.web;

import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Champs d'un formulaire envoye, avec lecture typee et validee. Toute valeur invalide leve une
 * {@link ValidationException} dont le message est affiche a l'utilisateur.
 */
public final class FormData {
    private static final BigDecimal MAX_AMOUNT = fr.patria.common.InputRules.MAX_AMOUNT;
    private final Map<String, String> values;
    private final Map<String, byte[]> files;

    public FormData(Map<String, String> values, Map<String, byte[]> files) {
        this.values = Map.copyOf(values);
        this.files = Map.copyOf(files);
    }

    static FormData urlEncoded(byte[] body) {
        Map<String, String> values = new HashMap<>();
        for (String item : new String(body, StandardCharsets.UTF_8).split("&")) {
            String[] entry = item.split("=", 2);
            if (entry.length != 2) continue;
            try {
                values.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8), URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                throw new ValidationException("Formulaire mal encodé.");
            }
        }
        return new FormData(values, Map.of());
    }

    /** Toutes les valeurs, pour reafficher un formulaire avec la saisie de l'utilisateur. */
    public Map<String, String> values() {
        return values;
    }

    public String raw(String name) {
        return values.getOrDefault(name, "");
    }

    public String requiredText(String name, String label, int maxLength) {
        String value = raw(name).trim();
        if (value.isEmpty()) throw new ValidationException(label + " est obligatoire.");
        if (value.length() > maxLength) throw new ValidationException(label + " : " + maxLength + " caractères maximum.");
        return fr.patria.common.InputRules.checkText(value, label);
    }

    public String optionalText(String name, String label, int maxLength) {
        String value = raw(name).trim();
        if (value.length() > maxLength) throw new ValidationException(label + " : " + maxLength + " caractères maximum.");
        return fr.patria.common.InputRules.checkText(value, label);
    }

    /** Montant positif ou nul ; accepte « 250 000,50 », « 250000.50 », « 250 000 € ». */
    public BigDecimal amount(String name, String label) {
        BigDecimal value = optionalAmount(name, label);
        if (value == null) throw new ValidationException(label + " est obligatoire.");
        return value;
    }

    /** Montant facultatif : {@code null} si le champ est vide. */
    public BigDecimal optionalAmount(String name, String label) {
        BigDecimal value = optionalDecimal(name, label);
        if (value == null) return null;
        if (value.signum() < 0) throw new ValidationException(label + " ne peut pas être négatif.");
        if (value.compareTo(MAX_AMOUNT) > 0) throw new ValidationException(label + " est trop élevé.");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal positiveAmount(String name, String label) {
        BigDecimal value = amount(name, label);
        if (value.signum() == 0) throw new ValidationException(label + " doit être supérieur à zéro.");
        return value;
    }

    /** Pourcentage saisi (« 8 » ou « 8,5 ») converti en ratio (0.08). */
    public BigDecimal percent(String name, String label, BigDecimal defaultRatio) {
        BigDecimal value = optionalDecimal(name, label);
        if (value == null) return defaultRatio;
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) throw new ValidationException(label + " doit être compris entre 0 et 100 %.");
        return value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public LocalDate date(String name, String label) {
        String value = raw(name).trim();
        if (value.isEmpty()) throw new ValidationException(label + " est obligatoire.");
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ValidationException(label + " invalide.");
        }
        return fr.patria.common.InputRules.checkDate(date, label);
    }

    /** Date facultative qui ne peut pas etre dans le futur. */
    public LocalDate pastDate(String name, String label, LocalDate today, LocalDate defaultValue) {
        if (raw(name).isBlank()) return defaultValue;
        LocalDate date = date(name, label);
        if (date.isAfter(today)) throw new ValidationException(label + " ne peut pas être dans le futur.");
        return date;
    }

    /**
     * Contenu a importer : la reponse collee dans {@code texte} (sans les balises de code qu'ajoutent les assistants
     * IA), sinon le fichier envoye dans {@code name}.
     */
    public byte[] uploadedText(String name, String label) {
        // Document depose pour l'IA locale : converti en lignes au format de cet import ({@code ia} : son type).
        byte[] document = files.get("document");
        if (document != null && document.length > 0 && !raw("ia").isBlank() && converter != null) {
            return converter.apply(raw("ia"), document).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        String pasted = raw("texte").strip();
        if (pasted.isEmpty()) return requiredFile(name, label);
        String cleaned = pasted.replaceAll("(?m)^```[a-zA-Z]*\\s*$", "").strip();
        if (cleaned.isEmpty()) throw new ValidationException("Collez la réponse de l'IA (les lignes au format demandé).");
        return cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static volatile java.util.function.BiFunction<String, byte[], String> converter;

    /** Conversion d'un document par l'IA locale (type d'import, contenu) ; {@code null} sans IA locale. Pose au demarrage. */
    public static void useConverter(java.util.function.BiFunction<String, byte[], String> value) {
        converter = value;
    }

    public byte[] requiredFile(String name, String label) {
        byte[] content = files.get(name);
        if (content == null || content.length == 0) throw new ValidationException(label + " manquant.");
        return content;
    }

    private BigDecimal optionalDecimal(String name, String label) {
        String value = raw(name).replaceAll("[\\s\\u00a0\\u202f€%]", "").replace(',', '.');
        if (value.isEmpty()) return null;
        BigDecimal number = fr.patria.common.InputRules.decimal(value);
        if (number == null) throw new ValidationException(label + " invalide : un nombre comme 1250 ou 1250,50.");
        return number;
    }
}
