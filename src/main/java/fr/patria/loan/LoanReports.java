// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.loan;

import static fr.patria.ui.Format.euro;
import static fr.patria.ui.PdfReport.row;

import fr.patria.property.Property;
import fr.patria.ui.Format;
import fr.patria.ui.PdfReport;
import fr.patria.ui.PdfReport.Metric;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Synthese PDF d'un pret : situation a date, bilan par annee, echeancier complet. */
final class LoanReports {
    private LoanReports() {
    }

    /** Totaux d'une annee de l'echeancier. */
    private static final class Year {
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal remaining;
    }

    static byte[] loan(Loan loan, Property property, List<ScheduleLine> lines, LoanMath.Status status, java.util.Optional<Renegotiation> renegotiation,
                       LocalDate today) {
        ScheduleLine next = status.nextInstallment();
        PdfReport report = PdfReport.create("Prêt · " + loan.name(), "Finance « " + property.name() + " »", today).metrics(List.of(
                Metric.of("Capital emprunté", euro(status.borrowed()), "taux actuel " + Format.percent(status.currentRate())),
                Metric.of("Capital restant dû", euro(status.remaining()), status.progressPercent() + " % remboursé"),
                Metric.of("Prochaine échéance", next == null ? Format.DASH : euro(next.amount()), next == null ? "aucune échéance à venir" : Format.date(next.date())),
                Metric.of("Durée restante", Format.duration(status.remainingMonths()), status.lastDate() == null ? null : "fin le " + Format.date(status.lastDate()))));
        report.section("Situation au " + Format.date(today)).lines(List.of(
                new String[]{"Capital remboursé", euro(status.repaid())},
                new String[]{"Intérêts payés", euro(status.interestPaid())},
                new String[]{"Intérêts restant à payer", euro(status.interestToPay())},
                new String[]{"Intérêts sur toute la durée", euro(status.interestTotal())},
                new String[]{"Coût du crédit déjà payé (intérêts, assurance, frais)", euro(status.creditCost())}), false);

        renegotiation.ifPresent(item -> report.section("Comparaison avec les taux actuels").lines(comparison(item), item.grossSaving().signum() > 0)
                .note("Taux moyen des nouveaux crédits immobiliers à taux fixe sur plus de 10 ans en France (BCE, données Banque de France), hors assurance ; "
                        + "précision d'environ 0,3 point." + (item.shortRemaining() ? " Il reste moins de 10 ans : comparaison indicative." : "")
                        + " Frais de dossier, de garantie et assurance non comptés. Repère usuel : renégocier à partir de 0,7 point d'écart."));

        Map<Integer, Year> years = new TreeMap<>();
        for (ScheduleLine line : lines) {
            if (line.type() == LineType.DRAWDOWN) continue;
            Year year = years.computeIfAbsent(line.date().getYear(), key -> new Year());
            year.paid = year.paid.add(zero(line.amount()));
            year.principal = year.principal.add(zero(line.principalRepaid()));
            year.interest = year.interest.add(zero(line.interest()));
            year.fees = year.fees.add(zero(line.fees()));
            if (line.remainingPrincipal() != null) year.remaining = line.remainingPrincipal();
        }
        List<List<String>> yearRows = new ArrayList<>();
        years.forEach((number, year) -> yearRows.add(row(number + (number < today.getYear() ? "" : number == today.getYear() ? " (en cours)" : ""),
                euro(year.paid), euro(year.principal), euro(year.interest), euro(year.fees), year.remaining == null ? Format.DASH : euro(year.remaining))));
        report.section("Par année").table(List.of("Année", "#Payé", "#Capital", "#Intérêts", "#Frais", "#Restant dû fin d'année"), yearRows,
                new float[]{1.3f, 1.1f, 1.1f, 1.1f, 1, 1.3f}, false);

        report.section("Échéancier complet").table(List.of("Date", "Type", "Statut", "#Montant", "#Capital", "#Intérêts", "#Frais", "#Restant dû"),
                lines.stream().map(line -> row(Format.date(line.date()), line.type().label(), LoanMath.isPast(line, today) ? "Passée" : "À venir",
                        euro(line.amount()), euro(line.principalRepaid()), euro(line.interest()), euro(line.fees()), euro(line.remainingPrincipal()))).toList(),
                new float[]{1, 1.5f, 0.8f, 1, 1, 0.9f, 0.8f, 1.1f}, false);
        report.note("D'après l'échéancier importé. Intérêts seuls : l'assurance et les frais sont dans la colonne Frais.");
        return report.toBytes();
    }

    private static List<String[]> comparison(Renegotiation item) {
        List<String[]> rows = new ArrayList<>(List.of(
                new String[]{"Votre taux", Format.percent(item.ownRate())},
                new String[]{"Taux moyen du marché (" + Format.month(item.month()).toLowerCase(java.util.Locale.FRENCH) + ")", Format.percent(item.marketRate())},
                new String[]{"Écart", (item.gap().signum() > 0 ? "+" : "") + Format.number(item.gap(), 2) + " point"}));
        if (item.grossSaving().signum() <= 0) {
            rows.add(new String[]{"Mensualité au taux du marché", euro(item.newPayment()) + " au lieu de " + euro(item.currentPayment())});
            rows.add(new String[]{"Avantage de votre taux (intérêts en moins)", Format.euroWhole(item.grossSaving().negate())});
        } else {
            rows.add(new String[]{"Mensualité hors assurance au taux du marché", euro(item.currentPayment()) + " -> " + euro(item.newPayment())});
            rows.add(new String[]{"Économie d'intérêts sur la durée restante", Format.signedEuroWhole(item.grossSaving())});
            rows.add(new String[]{"Pénalités de remboursement anticipé (plafond)", euro(item.penalty())});
            rows.add(new String[]{"Économie après pénalités", Format.signedEuroWhole(item.netSaving())});
        }
        return rows;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
