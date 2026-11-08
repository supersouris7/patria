package fr.patria.portfolio;

import static fr.patria.ui.Format.euro;
import static fr.patria.ui.Format.euroWhole;
import static fr.patria.ui.PdfReport.row;

import fr.patria.loan.LoanMath;
import fr.patria.market.MarketPrice;
import fr.patria.portfolio.Portfolio.Overview;
import fr.patria.portfolio.Portfolio.PropertySummary;
import fr.patria.portfolio.Portfolio.Returns;
import fr.patria.property.Property;
import fr.patria.ui.Format;
import fr.patria.ui.PdfReport;
import fr.patria.ui.PdfReport.Metric;
import fr.patria.ui.PdfReport.Tone;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Syntheses PDF de l'immobilier : vue d'ensemble du patrimoine et fiche d'un bien. */
final class PortfolioReports {
    private PortfolioReports() {
    }

    static byte[] overview(Overview overview, java.util.Optional<fr.patria.market.HousingRate> rate, LocalDate today) {
        PdfReport report = PdfReport.create("Patrimoine immobilier", Format.plural(overview.properties().size(), "bien", "biens"), today);
        if (overview.properties().isEmpty()) return report.paragraph("Aucun bien enregistré.").toBytes();
        report.metrics(List.of(
                Metric.of("Valeur estimée", euroWhole(overview.value()), "somme des valeurs retenues"),
                signed("Valeur nette", overview.net(), "après " + euroWhole(overview.debt()) + " de capital restant dû"),
                signed("Enrichissement réel", overview.returns().realGain(), multiple(overview.returns())),
                nextMonth(overview.nextMonth())));
        balance(report, overview.value(), overview.net(), overview.debt(), overview.purchase(), overview.gain(), overview.contributed(), overview.returns());

        List<List<String>> properties = new ArrayList<>();
        for (PropertySummary summary : overview.properties()) {
            Property property = summary.property();
            properties.add(row(property.name() + " (" + property.kind().label().toLowerCase(java.util.Locale.FRENCH) + ")",
                    property.commune().isBlank() ? Format.DASH : property.commune(), Format.area(property.area()),
                    euroWhole(summary.value()), euroWhole(summary.debt()), Format.signedEuroWhole(summary.net()), Format.signedEuroWhole(summary.returns().realGain())));
        }
        properties.add(row("Total", "", "", euroWhole(overview.value()), euroWhole(overview.debt()), Format.signedEuroWhole(overview.net()),
                Format.signedEuroWhole(overview.returns().realGain())));
        report.section("Biens").table(List.of("Bien", "Commune", "#Surface", "#Valeur", "#Capital dû", "#Valeur nette", "#Gain réel"), properties,
                new float[]{2.8f, 1.4f, 0.9f, 1.1f, 1.1f, 1.1f, 1.2f}, true);

        List<List<String>> loans = new ArrayList<>();
        for (PropertySummary summary : overview.properties()) {
            for (Portfolio.LoanSummary item : summary.loans()) loans.add(loanRow(item, summary.property().name()));
        }
        if (!loans.isEmpty()) {
            report.section("Prêts").table(List.of("Prêt", "Bien", "#Emprunté", "#Taux", "#Capital dû", "#Fin", "#Mensualité"), loans,
                    new float[]{2, 2, 1.1f, 0.8f, 1.1f, 1, 1.1f}, false);
            marketNote(report, rate);
        }
        if (!overview.propertyTaxes().isEmpty()) {
            report.section("Taxes foncières").table(List.of("Année", "#Biens", "Origine", "#Montant", "#Évolution"), overview.propertyTaxes().stream().limit(6)
                    .map(year -> row(year.year(), year.properties(), year.estimated() == 0 ? "Saisie" : year.entered() == 0 ? "Estimée" : "Mixte",
                            euroWhole(year.amount()), year.evolution() == null ? Format.DASH : Format.signedPercent(year.evolution()))).toList(),
                    new float[]{1, 0.8f, 1.2f, 1.2f, 1}, false);
        }
        report.note("Valeurs retenues sur la fiche de chaque bien, actualisées selon l'évolution des prix du département (DVF). Frais de vente selon le taux de chaque bien.");
        return report.toBytes();
    }

    static byte[] property(PropertySummary summary, java.util.Optional<fr.patria.market.HousingRate> rate, LocalDate today) {
        Property property = summary.property();
        String subtitle = property.kind().label() + (property.commune().isBlank() ? "" : " · " + property.commune())
                + (property.area() == null ? "" : " · " + Format.area(property.area())) + " · acquis le " + Format.date(property.purchaseDate());
        PdfReport report = PdfReport.create(property.name(), subtitle, today).metrics(List.of(
                Metric.of("Valeur retenue", euroWhole(summary.value()), summary.scenario()),
                signed("Valeur nette", summary.net(), "après " + euroWhole(summary.debt()) + " de capital dû"),
                signed("Enrichissement réel", summary.returns().realGain(), multiple(summary.returns())),
                nextMonth(summary.nextMonth())));
        if (!summary.scenarios().isEmpty()) {
            report.section("Scénarios de valeur").table(List.of("#Prudent", "#Prix du marché", "#Fourchette", "#Optimiste"), List.of(row(
                    euroWhole(summary.scenarios().prudent()), summary.scenarios().market() == null ? "—" : euroWhole(summary.scenarios().market()),
                    euroWhole(summary.scenarios().middle()),
                    euroWhole(summary.scenarios().optimistic()))), null, false);
            if (summary.revaluation().factor() != null) {
                report.note("Estimation du " + Format.date(summary.revaluation().since()) + " (" + euroWhole(summary.revaluation().base())
                        + "), actualisée de " + Format.signedPercent(summary.revaluation().percent()) + " selon l'indice DVF du département.");
            }
        }
        balance(report, summary.value(), summary.net(), summary.debt(), property.purchase(), summary.gain(), summary.contributed(), summary.returns());

        if (!summary.loans().isEmpty()) {
            report.section("Prêts").table(List.of("Prêt", "Bien", "#Emprunté", "#Taux", "#Capital dû", "#Fin", "#Mensualité"),
                    summary.loans().stream().map(item -> loanRow(item, property.name())).toList(), new float[]{2, 2, 1.1f, 0.8f, 1.1f, 1, 1.1f}, false);
            marketNote(report, rate);
        }
        if (!summary.contributions().isEmpty()) {
            report.section("Apports").table(List.of("Apport", "Date", "#Montant"), summary.contributions().stream()
                    .map(item -> row(item.name(), Format.date(item.date()), euroWhole(item.amount()))).toList(), new float[]{3, 1.2f, 1.2f}, false);
        }
        Portfolio.SquareMeter sqm = summary.squareMeter();
        MarketPrice market = sqm.market();
        List<String[]> squareMeter = new ArrayList<>();
        squareMeter.add(new String[]{"Votre bien (valeur retenue ÷ surface)", Format.perSquareMeter(sqm.value())});
        if (sqm.purchase() != null) squareMeter.add(new String[]{"Acquisition", Format.perSquareMeter(sqm.purchase())});
        if (market != null && market.isAvailable()) {
            squareMeter.add(new String[]{"Marché : médiane de " + market.salesCount() + " ventes " + (market.scope() == null ? "" : market.scope().label()),
                    Format.perSquareMeter(market.median())});
            squareMeter.add(new String[]{"Fourchette du marché (1er à 3e quartile)", Format.perSquareMeter(market.low()) + " à " + Format.perSquareMeter(market.high())});
            if (sqm.gapPercent() != null) squareMeter.add(new String[]{"Écart avec le marché", Format.signedPercent(sqm.gapPercent())});
        }
        report.section("Prix au m²").lines(squareMeter, false);
        if (market != null && market.isAvailable()) {
            report.note("Source : ventes DVF (DGFiP, Etalab) sur data.gouv.fr, " + market.place() + ", " + market.period() + ", calculé le " + Format.date(market.computedOn()) + ".");
        }
        return report.toBytes();
    }

    /** Detail de la valeur nette et de l'enrichissement reel, comme le bilan de la page. */
    private static void balance(PdfReport report, BigDecimal value, BigDecimal net, BigDecimal debt, BigDecimal purchase, BigDecimal gain,
                                BigDecimal contributed, Returns returns) {
        BigDecimal saleFees = value.subtract(net).subtract(debt);
        report.section("Valeur nette").lines(List.of(
                new String[]{"Valeur retenue", euroWhole(value)},
                new String[]{"Frais de vente et fiscalité", minus(saleFees)},
                new String[]{"Capital restant dû", minus(debt)},
                new String[]{"Valeur nette indicative", euroWhole(net)}), true);
        report.note("Plus-value latente (valeur − coût d'acquisition, hors frais de vente) : " + Format.signedEuroWhole(gain) + ".");
        report.section("Enrichissement réel depuis l'achat").lines(List.of(
                new String[]{"Valeur après frais de vente", euroWhole(value.subtract(saleFees))},
                new String[]{"Coût d'acquisition", minus(purchase)},
                new String[]{"Coût du crédit payé", minus(returns.creditCost())},
                new String[]{"Charges payées", minus(returns.expenses())},
                new String[]{"Loyers perçus", "+ " + euroWhole(returns.rents())},
                new String[]{"Loyer économisé (résidence principale)", "+ " + euroWhole(returns.savedRent())},
                new String[]{"Enrichissement réel", Format.signedEuroWhole(returns.realGain())}), true);
        String rent = returns.monthlyRent().signum() == 0 ? Format.DASH : euroWhole(returns.monthlyRent()) + "/mois";
        report.section("Repères").lines(List.of(
                new String[]{"Apports", euroWhole(contributed)},
                new String[]{"Argent investi", euroWhole(returns.invested())},
                new String[]{"Multiple (valeur nette ÷ argent investi)", Format.multiple(returns.multiple())},
                new String[]{"Occupation", returns.occupancy()},
                new String[]{"Loyer perçu actuel", rent},
                new String[]{"Rendement brut", Format.percent(returns.grossYield())},
                new String[]{"Charges de l'année", euroWhole(returns.yearExpenses())}), false);
    }

    private static void marketNote(PdfReport report, java.util.Optional<fr.patria.market.HousingRate> rate) {
        rate.ifPresent(value -> report.note("Taux moyen actuel des nouveaux crédits immobiliers à taux fixe sur plus de 10 ans : " + Format.percent(value.rate())
                + " (" + Format.month(value.month()).toLowerCase(java.util.Locale.FRENCH) + ", BCE, hors assurance). Détail par prêt dans sa synthèse."));
    }

    private static List<String> loanRow(Portfolio.LoanSummary item, String property) {
        LoanMath.Status status = item.status();
        return row(item.loan().name(), property, euroWhole(status.borrowed()), Format.percent(status.currentRate()), euroWhole(status.remaining()),
                status.lastDate() == null ? Format.DASH : Format.date(status.lastDate()),
                status.nextInstallment() == null ? Format.DASH : euro(status.nextInstallment().amount()));
    }

    private static Metric signed(String label, BigDecimal value, String sub) {
        return new Metric(label, Format.signedEuroWhole(value), sub, value.signum() < 0 ? Tone.NEGATIVE : value.signum() > 0 ? Tone.POSITIVE : Tone.NEUTRAL);
    }

    private static Metric nextMonth(LoanMath.Month next) {
        return next == null ? Metric.of("Prochaine mensualité", Format.DASH, "aucune échéance à venir")
                : Metric.of("Prochaine mensualité", euro(next.total()), Format.month(next.month()));
    }

    private static String multiple(Returns returns) {
        return (returns.multiple() == null ? "" : Format.multiple(returns.multiple()) + " · ") + euroWhole(returns.invested()) + " investis";
    }

    private static String minus(BigDecimal value) {
        return value == null || value.signum() == 0 ? euroWhole(BigDecimal.ZERO) : "- " + euroWhole(value);
    }
}
