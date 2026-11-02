package fr.patria;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Mise en forme a la francaise de tout ce qui est affiche. Chaque sortie est deja echappee pour le HTML. */
final class Format {
    private static final Locale FR = Locale.FRANCE;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", FR);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", FR);

    private Format() {
    }

    static String euro(BigDecimal value) {
        if (value == null) return "–";
        NumberFormat format = NumberFormat.getCurrencyInstance(FR);
        return escape(format.format(value));
    }

    /** Montant sans centimes, pour les grandes valeurs (estimations, patrimoine). */
    static String euroWhole(BigDecimal value) {
        if (value == null) return "–";
        NumberFormat format = NumberFormat.getCurrencyInstance(FR);
        format.setMaximumFractionDigits(0);
        format.setMinimumFractionDigits(0);
        return escape(format.format(value));
    }

    static String signedEuroWhole(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + euroWhole(value);
    }

    static String number(BigDecimal value, int maxDecimals) {
        if (value == null) return "–";
        NumberFormat format = NumberFormat.getNumberInstance(FR);
        format.setMaximumFractionDigits(maxDecimals);
        return escape(format.format(value));
    }

    /** Taux exprime en pourcentage (ex. 3.2 -> "3,2 %"). */
    static String percent(BigDecimal value) {
        return value == null ? "–" : number(value, 2) + " %";
    }

    /** Taux exprime en ratio (ex. 0.08 -> "8 %"). */
    static String ratioPercent(BigDecimal ratio) {
        return ratio == null ? "–" : percent(ratio.multiply(BigDecimal.valueOf(100)));
    }

    static String area(BigDecimal value) {
        return value == null || value.signum() == 0 ? "–" : number(value, 2) + " m²";
    }

    static String date(LocalDate date) {
        return date == null ? "–" : date.format(DATE);
    }

    static String month(YearMonth month) {
        String text = month.format(MONTH);
        return escape(Character.toUpperCase(text.charAt(0)) + text.substring(1));
    }

    static String duration(long months) {
        if (months <= 0) return "Terminé";
        long years = months / 12;
        long rest = months % 12;
        String result = years == 0 ? "" : years + (years > 1 ? " ans" : " an");
        if (rest > 0) result += (result.isEmpty() ? "" : " ") + rest + " mois";
        return result;
    }

    /** Valeur brute pour un champ de formulaire ou un attribut data-*. */
    static String plain(BigDecimal value) {
        if (value == null) return "";
        return value.setScale(Math.max(0, Math.min(value.scale(), 6)), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
