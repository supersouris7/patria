// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.wizard;

import fr.patria.common.Frequency;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

/**
 * Revue annuelle : ce qui change d'une annee sur l'autre (salaires, taxe fonciere, abonnements, estimation des biens),
 * le calendrier des mises a jour et la regle du rappel. Fonctions pures.
 */
final class AnnualReview {
    /** Un mois du calendrier et ce qu'il faut y mettre a jour. */
    record CalendarMonth(Month month, String what) {
    }

    static final List<CalendarMonth> CALENDAR = List.of(
            new CalendarMonth(Month.JANUARY, "Salaires (augmentations), mutuelle, assurances auto et habitation renouvelées, forfaits revalorisés."),
            new CalendarMonth(Month.FEBRUARY, "Échéanciers d'électricité et de gaz (nouveaux tarifs réglementés)."),
            new CalendarMonth(Month.MARCH, "Primes annuelles versées, nouvelle mensualité de crédit si taux variable."),
            new CalendarMonth(Month.APRIL, "Déclaration de revenus : rassemblez bulletins et attestations de l'année passée."),
            new CalendarMonth(Month.MAY, "Déclaration de revenus (fin des délais en mai ou début juin)."),
            new CalendarMonth(Month.JUNE, "Charges de copropriété : régularisation après l'assemblée générale."),
            new CalendarMonth(Month.JULY, "Avis d'impôt sur le revenu : solde à payer ou remboursement en septembre."),
            new CalendarMonth(Month.AUGUST, "Rentrée : cantine, périscolaire, activités et assurance scolaire."),
            new CalendarMonth(Month.SEPTEMBER, "Nouveau taux de prélèvement à la source (montant sur le bulletin de paie)."),
            new CalendarMonth(Month.OCTOBER, "Avis de taxe foncière de chaque bien."),
            new CalendarMonth(Month.NOVEMBER, "Taxe d'habitation (résidence secondaire), estimation des biens avant la fin d'année."),
            new CalendarMonth(Month.DECEMBER, "Revue annuelle : abonnements résiliés ou nouveaux, budget de l'année à venir."));

    /** Delai apres lequel une nouvelle revue est conseillee. */
    static final int DAYS = 365;

    private AnnualReview() {
    }

    /** Ce qu'il faut verifier ce mois-ci. */
    static CalendarMonth thisMonth(LocalDate today) {
        return CALENDAR.get(today.getMonthValue() - 1);
    }

    /**
     * Revue conseillee : derniere revue (ou, a defaut, premier passage dans le guide, ou plus ancienne donnee saisie)
     * il y a plus d'un an. Rien a revoir sans donnee.
     */
    static boolean isDue(LocalDate reviewedOn, LocalDate firstActivity, LocalDate oldestData, LocalDate today) {
        LocalDate reference = reviewedOn != null ? reviewedOn : firstActivity != null ? firstActivity : oldestData;
        return reference != null && reference.plusDays(DAYS).isBefore(today);
    }

    /** Prochaine echeance d'une depense ou d'un revenu recurrent a partir d'aujourd'hui (le mois de debut fixe le rythme). */
    static LocalDate nextOccurrence(LocalDate start, Frequency frequency, LocalDate today) {
        int months = switch (frequency) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case SEMIANNUAL -> 6;
            case ANNUAL -> 12;
            case ONCE -> 0;
        };
        if (months == 0 || !start.isBefore(today)) return start;
        LocalDate date = start;
        while (date.isBefore(today.withDayOfMonth(1))) date = date.plusMonths(months);
        return date;
    }
}
