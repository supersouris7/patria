package fr.patria.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Mise en forme a la francaise. Renvoie du texte brut : l'echappement est fait par {@link Html}. */
public final class Format {
    public static final String DASH = "–";
    private static final Locale FR = Locale.FRANCE;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", FR);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", FR);

    private Format() {
    }

    /** Montant au centime : « 1 234,56 € ». */
    public static String euro(BigDecimal value) {
        return value == null ? DASH : NumberFormat.getCurrencyInstance(FR).format(value);
    }

    /** Montant arrondi a l'euro, pour les grandes valeurs : « 320 000 € ». */
    public static String euroWhole(BigDecimal value) {
        if (value == null) return DASH;
        NumberFormat format = NumberFormat.getCurrencyInstance(FR);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(0);
        return format.format(value);
    }

    /** Montant signe : « +20 000 € », « −5 000 € ». */
    public static String signedEuroWhole(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + euroWhole(value);
    }

    public static String number(BigDecimal value, int maxDecimals) {
        if (value == null) return DASH;
        NumberFormat format = NumberFormat.getNumberInstance(FR);
        format.setMaximumFractionDigits(maxDecimals);
        return format.format(value);
    }

    /** Taux deja exprime en pourcentage : 3.2 -> « 3,2 % ». */
    public static String percent(BigDecimal value) {
        return value == null ? DASH : number(value, 2) + " %";
    }

    /** Taux exprime en ratio : 0.08 -> « 8 % ». */
    public static String ratioPercent(BigDecimal ratio) {
        return ratio == null ? DASH : percent(ratio.multiply(BigDecimal.valueOf(100)));
    }

    public static String area(BigDecimal value) {
        return value == null || value.signum() == 0 ? DASH : number(value, 2) + " m²";
    }

    public static String date(LocalDate date) {
        return date == null ? DASH : date.format(DATE);
    }

    public static String month(YearMonth month) {
        String text = month.format(MONTH);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Duree en mois : 250 -> « 20 ans 10 mois ». */
    public static String duration(long months) {
        if (months <= 0) return "Terminé";
        long years = months / 12;
        long rest = months % 12;
        String result = years == 0 ? "" : years + (years > 1 ? " ans" : " an");
        if (rest > 0) result += (result.isEmpty() ? "" : " ") + rest + " mois";
        return result;
    }

    public static String plural(long count, String singular, String plural) {
        return count + " " + (count > 1 ? plural : singular);
    }

    /** Valeur brute pour un champ de formulaire ou un attribut data-* (« 320000 », « 0.08 »). */
    public static String plain(BigDecimal value) {
        if (value == null) return "";
        return value.setScale(Math.max(0, Math.min(value.scale(), 6)), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
