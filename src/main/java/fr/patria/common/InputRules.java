package fr.patria.common;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Regles de saisie communes aux formulaires et aux imports CSV, pour qu'une meme valeur soit acceptee ou refusee
 * partout de la meme facon.
 *
 * <ul>
 *   <li>nombres : chiffres, un separateur decimal, signe moins facultatif ; pas de notation scientifique (« 1e5 »)
 *       ni d'hexadecimal, qui donneraient des montants inattendus ;</li>
 *   <li>montants : au plus {@link #MAX_AMOUNT} (un milliard), pour rester bien en dessous des limites de stockage ;</li>
 *   <li>dates : de 1900 a 2100, pour ecarter les fautes de frappe (an 0, 20260) qui fausseraient les calculs ;</li>
 *   <li>textes : sans caracteres de controle (invisibles, retours a la ligne colles depuis un tableur).</li>
 * </ul>
 */
public final class InputRules {
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
    public static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+([.][0-9]+)?|-?[.][0-9]+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private InputRules() {
    }

    /** Nombre ecrit simplement (« 1250.50 », apres nettoyage des espaces et de la virgule) ; {@code null} s'il est illisible. */
    public static BigDecimal decimal(String cleaned) {
        if (cleaned == null || !DECIMAL.matcher(cleaned).matches()) return null;
        return new BigDecimal(cleaned);
    }

    public static boolean tooLarge(BigDecimal amount) {
        return amount.abs().compareTo(MAX_AMOUNT) > 0;
    }

    /** Refuse une date hors de 1900–2100. */
    public static LocalDate checkDate(LocalDate date, String label) {
        if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
            throw new ValidationException(label + " : date hors limites (entre 1900 et 2100).");
        }
        return date;
    }

    /** Refuse un texte contenant des caracteres de controle. */
    public static String checkText(String value, String label) {
        if (CONTROL.matcher(value).find()) throw new ValidationException(label + " contient des caractères non autorisés (retour à la ligne, tabulation…).");
        return value;
    }
}
