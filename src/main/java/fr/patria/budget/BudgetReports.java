package fr.patria.budget;

import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;
import static fr.patria.ui.PdfReport.row;

import fr.patria.ui.Format;
import fr.patria.ui.PdfReport;
import fr.patria.ui.PdfReport.Metric;
import fr.patria.ui.PdfReport.Tone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Syntheses PDF du Previsionnel et du Budget moyen, pour la vue affichee (foyer ou membre). Aucun calcul : la vue est deja calculee. */
final class BudgetReports {
    private BudgetReports() {
    }

    /** Previsionnel : le mois en cours, les alertes, les 12 mois et le detail du mois en cours. */
    static byte[] forecast(Forecast.View view, LocalDate today) {
        Forecast.Month current = view.current();
        PdfReport report = PdfReport.create("Prévisionnel · " + Format.month(current.month()), BudgetPages.owner(view), today)
                .metrics(List.of(
                        Metric.of("Revenus du mois", euroWhole(current.income()), Format.plural(current.incomes().size(), "versement", "versements")),
                        Metric.of("Dépenses du mois", euroWhole(current.spending()), Format.plural(current.expenses().size(), "dépense", "dépenses")),
                        balance("Reste à vivre", current.balance(), "revenus − dépenses du mois"),
                        debt(view)));
        if (view.provision().signum() > 0) report.paragraph("À mettre de côté chaque mois pour les dépenses non mensuelles : " + euroWhole(view.provision()) + ".");
        if (!view.alerts().isEmpty()) {
            report.section("À surveiller").bullets(view.alerts().stream().sorted(Comparator.comparing(Forecast.Alert::serious).reversed())
                    .map(alert -> (alert.serious() ? "!" : "") + alert.text()).toList());
        }
        List<List<String>> months = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spending = BigDecimal.ZERO;
        for (Forecast.Month month : view.months()) {
            months.add(row(Format.month(month.month()), euroWhole(month.income()), euroWhole(month.spending()), Format.signedEuroWhole(month.balance())));
            income = income.add(month.income());
            spending = spending.add(month.spending());
        }
        months.add(row("Total sur " + view.months().size() + " mois", euroWhole(income), euroWhole(spending), Format.signedEuroWhole(income.subtract(spending))));
        report.section("Les " + view.months().size() + " prochains mois").table(List.of("Mois", "#Revenus", "#Dépenses", "#Reste à vivre"), months, new float[]{2.2f, 1, 1, 1}, true);

        report.section("Détail de " + Format.month(current.month()).toLowerCase(java.util.Locale.FRENCH));
        report.table(List.of("Revenu", "Personne", "#Montant"), current.incomes().stream()
                .map(line -> row(line.label(), line.who(), euro(line.amount()))).toList(), new float[]{3, 1.6f, 1.2f}, false);
        report.table(List.of("Dépense", "Catégorie", "Rythme", "#Montant"), current.expenses().stream()
                .sorted(Comparator.comparing(Forecast.Line::category).thenComparing(Forecast.Line::amount, Comparator.reverseOrder()))
                .map(line -> row(line.label(), line.category().label(), line.frequency().label(), euro(line.amount()))).toList(), new float[]{3.4f, 1.6f, 1.1f, 1.2f}, false);
        report.note("Prêts, charges et loyers des biens sont repris de l'immobilier. Montants nets avant impôt sur le revenu.");
        return report.toBytes();
    }

    /** Budget moyen : moyennes mensuelles sur 12 mois, endettement, categories, revenus et depenses. */
    static byte[] average(Forecast.View view, List<Forecast.Average> incomes, List<Forecast.Average> expenses, LocalDate today) {
        BigDecimal income = incomes.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spending = expenses.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        PdfReport report = PdfReport.create("Budget moyen", BudgetPages.owner(view) + " · moyenne par mois sur les 12 prochains mois", today)
                .metrics(List.of(
                        Metric.of("Revenus", euroWhole(income), "par mois en moyenne"),
                        Metric.of("Dépenses", euroWhole(spending), "par mois en moyenne"),
                        balance("Reste à vivre", income.subtract(spending), "par mois en moyenne"),
                        Metric.of("À mettre de côté", euroWhole(view.provision()), "chaque mois")));

        boolean account = view.person() != null && view.person().isAccount();
        Forecast.DebtRatio debt = view.debt();
        if (!account && debt.percent() != null) {
            BigDecimal margin = debt.marginToComfort();
            report.section("Taux d'endettement : " + Format.percent(debt.percent())).lines(List.<String[]>of(
                    new String[]{"Mensualités de crédit", euroWhole(debt.credits()) + "/mois"},
                    new String[]{"Revenus retenus (loyers à 70 %)", euroWhole(debt.income()) + "/mois"},
                    new String[]{"Marge avant 33 %", margin.signum() == 0 ? "aucune" : euroWhole(margin) + "/mois"}), false)
                    .note(BudgetPages.DEBT_HINT);
        }

        Map<Category, BigDecimal> byCategory = new EnumMap<>(Category.class);
        for (Forecast.Average line : expenses) byCategory.merge(line.category(), line.monthly(), BigDecimal::add);
        List<List<String>> categories = new ArrayList<>(byCategory.entrySet().stream().sorted(Map.Entry.<Category, BigDecimal>comparingByValue().reversed())
                .map(entry -> row(entry.getKey().label(), euroWhole(entry.getValue()),
                        spending.signum() == 0 ? "" : Format.percent(entry.getValue().multiply(BigDecimal.valueOf(100)).divide(spending, 0, RoundingMode.HALF_UP))))
                .toList());
        if (!categories.isEmpty()) categories.add(row("Total", euroWhole(spending), "100 %"));
        report.section("Dépenses par catégorie").table(List.of("Catégorie", "#Par mois", "#Part"), categories, new float[]{3, 1.2f, 0.8f}, !categories.isEmpty());

        List<List<String>> incomeRows = new ArrayList<>(incomes.stream()
                .map(line -> row(line.label(), line.detail(), line.frequency().label(), euro(line.perPayment()), euro(line.monthly()))).toList());
        if (!incomeRows.isEmpty()) incomeRows.add(row("Total", "", "", "", euro(income)));
        report.section("Revenus").table(List.of("Revenu", "Personne", "Rythme", "#Montant", "#Par mois"), incomeRows, new float[]{2.6f, 1.6f, 1, 1.1f, 1.1f}, !incomeRows.isEmpty());

        List<List<String>> expenseRows = new ArrayList<>(expenses.stream()
                .sorted(Comparator.comparing(Forecast.Average::category).thenComparing(Forecast.Average::monthly, Comparator.reverseOrder()))
                .map(line -> row(line.label() + (line.origin() == Forecast.Origin.REAL_ESTATE ? " (immobilier)" : ""), line.category().label(),
                        line.frequency().label(), euro(line.perPayment()), euro(line.monthly()))).toList());
        if (!expenseRows.isEmpty()) expenseRows.add(row("Total", "", "", "", euro(spending)));
        report.section("Dépenses").table(List.of("Dépense", "Catégorie", "Rythme", "#Montant", "#Par mois"), expenseRows, new float[]{3, 1.6f, 1, 1.1f, 1.1f}, !expenseRows.isEmpty());
        report.note("Chaque dépense est ramenée à son équivalent mensuel sur les 12 prochains mois.");
        return report.toBytes();
    }

    private static Metric balance(String label, BigDecimal balance, String sub) {
        return new Metric(label, Format.signedEuroWhole(balance), balance.signum() < 0 ? "dépenses supérieures aux revenus" : sub,
                balance.signum() < 0 ? Tone.NEGATIVE : Tone.POSITIVE);
    }

    private static Metric debt(Forecast.View view) {
        if (view.person() != null && view.person().isAccount()) return Metric.of("Taux d'endettement", Format.DASH, "pas de taux pour un compte");
        Forecast.DebtRatio debt = view.debt();
        if (debt.percent() == null) return Metric.of("Taux d'endettement", Format.DASH, "revenus non renseignés");
        return new Metric("Taux d'endettement", Format.percent(debt.percent()), debt.isAboveLimit() ? "au-dessus de 35 %" : "seuil bancaire : 35 %",
                debt.isAboveLimit() ? Tone.NEGATIVE : Tone.NEUTRAL);
    }
}
