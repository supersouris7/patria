package fr.patria.household;

import fr.patria.common.Frequency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Virement entre deux membres du foyer (par exemple d'une personne vers le compte joint), recurrent ou ponctuel :
 * une depense pour celui qui envoie, une recette pour celui qui recoit, sans effet sur le total du foyer.
 *
 * @param label libelle libre, vide pour « Virement »
 * @param start premier virement : son mois fixe le cycle ; seule date d'un virement ponctuel
 * @param end   dernier virement possible, {@code null} si sans fin
 */
public record Transfer(long id, long fromId, long toId, String label, BigDecimal amount, Frequency frequency, LocalDate start, LocalDate end) {
    public boolean paidIn(YearMonth month) {
        return frequency.occursIn(YearMonth.from(start), end == null ? null : YearMonth.from(end), month);
    }
}
