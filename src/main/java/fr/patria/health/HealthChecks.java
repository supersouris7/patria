// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import static fr.patria.ui.Format.euroWhole;

import fr.patria.budget.Category;
import fr.patria.budget.Forecast;
import fr.patria.common.Frequency;
import fr.patria.health.Check.Domain;
import fr.patria.health.Check.Level;
import fr.patria.household.Income;
import fr.patria.household.IncomeKind;
import fr.patria.loan.Renegotiation;
import fr.patria.portfolio.Portfolio;
import fr.patria.ui.Format;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Regles du bilan de sante. Fonctions pures : elles lisent des chiffres deja calcules (previsionnel, patrimoine) et
 * renvoient des points classes par domaine. Les seuils sont des reperes d'usage courant (HCSF pour l'endettement,
 * micro-foncier, IFI), rappeles dans chaque explication.
 */
final class HealthChecks {
    static final BigDecimal MICRO_FONCIER = new BigDecimal("15000");
    static final BigDecimal IFI_THRESHOLD = new BigDecimal("1300000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private HealthChecks() {
    }

    // ---- Finances ----

    static List<Check> finances(Forecast.View view, List<Forecast.Average> incomes, List<Forecast.Average> expenses, List<Income> rawIncomes, LocalDate today) {
        List<Check> checks = new ArrayList<>();
        BigDecimal income = sum(incomes);
        BigDecimal spending = sum(expenses);
        if (income.signum() == 0 && spending.signum() == 0) {
            checks.add(Check.of("budget-empty", Domain.FINANCES, Level.INFO, "Budget vide", "Aucun revenu ni dépense : le bilan des finances se remplira avec le guide de saisie.")
                    .link("/assistant", "Ouvrir le guide de saisie"));
            return checks;
        }
        BigDecimal balance = income.subtract(spending);
        if (balance.signum() < 0) {
            checks.add(Check.of("balance", Domain.FINANCES, Level.BAD, "Reste à vivre négatif",
                    "En moyenne, les dépenses dépassent les revenus de " + euroWhole(balance.negate()) + " par mois sur les 12 prochains mois.")
                    .link("/budget", "Voir le budget moyen"));
        } else if (income.signum() > 0 && balance.multiply(BigDecimal.TEN).compareTo(income) < 0) {
            checks.add(Check.of("balance", Domain.FINANCES, Level.WARN, "Reste à vivre faible",
                    "Il reste " + euroWhole(balance) + " par mois en moyenne, moins de 10 % des revenus : peu de marge pour un imprévu.").link("/budget", "Voir le budget moyen"));
        } else {
            checks.add(Check.of("balance", Domain.FINANCES, Level.GOOD, "Reste à vivre", "Il reste " + euroWhole(balance) + " par mois en moyenne, après toutes les dépenses."));
        }

        List<Forecast.Month> deficits = view.months().stream().filter(month -> month.balance().signum() < 0).toList();
        if (!deficits.isEmpty()) {
            String months = String.join(", ", deficits.stream().map(month -> Format.month(month.month()).toLowerCase(Locale.FRENCH)).toList());
            checks.add(Check.of("deficit-months", Domain.FINANCES, deficits.size() >= 3 ? Level.BAD : Level.WARN,
                    Format.plural(deficits.size(), "mois en déficit", "mois en déficit") + " sur 12",
                    "Les dépenses dépassent les revenus en " + months + ". Prévoyez de mettre de côté avant ces mois.").link("/", "Voir le prévisionnel"));
        }

        Forecast.DebtRatio debt = view.debt();
        boolean account = view.person() != null && view.person().isAccount();
        if (!account) {
            if (debt.percent() == null) {
                checks.add(Check.of("debt-ratio", Domain.FINANCES, Level.INFO, "Taux d'endettement", "Non calculé : renseignez les revenus."));
            } else {
                Level level = switch (debt.level()) {
                    case GOOD -> Level.GOOD;
                    case WARNING -> Level.WARN;
                    case SERIOUS -> Level.BAD;
                };
                checks.add(Check.of("debt-ratio", Domain.FINANCES, level, "Taux d'endettement : " + Format.percent(debt.percent()),
                        "Méthode des banques (HCSF) : sous 33 % confortable, 35 % au plus pour un nouveau crédit. "
                                + (debt.marginToComfort().signum() > 0 ? "Marge avant 33 % : " + euroWhole(debt.marginToComfort()) + " de mensualité." : "Plus de marge avant 33 %.")));
            }
        }

        BigDecimal cushionLow = spending.multiply(BigDecimal.valueOf(3)).setScale(0, RoundingMode.HALF_UP);
        BigDecimal cushionHigh = spending.multiply(BigDecimal.valueOf(6)).setScale(0, RoundingMode.HALF_UP);
        checks.add(Check.of("savings", Domain.FINANCES, Level.INFO, "Épargne de précaution",
                "Repère courant : 3 à 6 mois de dépenses de côté, soit " + euroWhole(cushionLow) + " à " + euroWhole(cushionHigh) + ". Patria ne connaît pas votre épargne : "
                        + "à comparer avec vos livrets." + (view.provision().signum() > 0 ? " Pour les dépenses non mensuelles, mettez aussi " + euroWhole(view.provision()) + " de côté chaque mois." : "")));

        BigDecimal fixed = expenses.stream().filter(line -> line.frequency() != Frequency.ONCE).map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (income.signum() > 0) {
            BigDecimal share = fixed.multiply(HUNDRED).divide(income, 0, RoundingMode.HALF_UP);
            Level level = share.compareTo(BigDecimal.valueOf(70)) > 0 ? Level.BAD : share.compareTo(BigDecimal.valueOf(50)) > 0 ? Level.WARN : Level.GOOD;
            checks.add(Check.of("fixed-expenses", Domain.FINANCES, level, "Dépenses fixes : " + share + " % des revenus",
                    euroWhole(fixed) + " par mois reviennent quoi qu'il arrive (crédits, logement, abonnements, assurances…). Au-delà de 50 %, la marge se réduit ; "
                            + "au-delà de 70 %, un imprévu déséquilibre le budget.").link("/budget", "Voir les dépenses par catégorie"));
        }

        List<Forecast.Average> subscriptions = expenses.stream().filter(line -> line.category() == Category.TELECOM || line.category() == Category.LEISURE)
                .filter(line -> line.frequency() != Frequency.ONCE).toList();
        if (!subscriptions.isEmpty()) {
            BigDecimal total = sum(subscriptions);
            boolean heavy = income.signum() > 0 && total.multiply(BigDecimal.valueOf(20)).compareTo(income) > 0;
            checks.add(Check.of("subscriptions", Domain.FINANCES, heavy ? Level.WARN : Level.INFO, "Abonnements et loisirs : " + euroWhole(total) + " par mois",
                    Format.plural(subscriptions.size(), "abonnement ou dépense régulière", "abonnements ou dépenses régulières") + (heavy ? ", plus de 5 % des revenus." : ".")
                            + " Vérifiez ceux qui ne servent plus : ils se résilient en quelques clics.").link("/assistant/revue-annuelle", "Faire la revue annuelle"));
        }

        long stale = rawIncomes.stream().filter(line -> line.end() == null && line.kind() == IncomeKind.SALARY && line.start().plusYears(1).isBefore(today)).count();
        if (stale > 0) {
            checks.add(Check.of("salary-outdated", Domain.FINANCES, Level.INFO, Format.plural(stale, "salaire inchangé", "salaires inchangés") + " depuis plus d'un an",
                    "Une augmentation ou un changement de poste ? Mettez le montant à jour pour garder un prévisionnel juste.").link("/assistant/revue-annuelle", "Faire la revue annuelle"));
        }
        return checks;
    }

    // ---- Biens ----

    static List<Check> properties(Portfolio.Overview overview, Renegotiation.Reference market, LocalDate today) {
        List<Check> checks = new ArrayList<>();
        for (Portfolio.PropertySummary summary : overview.properties()) {
            String name = summary.property().name();
            String page = "/bien/" + summary.property().id();
            String key = "property-" + summary.property().id() + "-";
            if (summary.estimateOutdated()) {
                checks.add(Check.of(key + "estimate-outdated", Domain.PROPERTIES, Level.WARN, name + " : estimation ancienne",
                        "Estimée le " + Format.date(summary.property().estimateDate()) + ", il y a plus de 3 ans : l'actualisation par l'indice a ses limites. "
                                + "Une nouvelle estimation (agence, site d'estimation) rendra la valeur plus fiable.").link("/assistant/revue-annuelle#biens", "Mettre à jour l'estimation"));
            }
            BigDecimal gap = summary.squareMeter().gapPercent();
            if (gap != null && gap.compareTo(BigDecimal.valueOf(15)) > 0) {
                checks.add(Check.of(key + "market-gap", Domain.PROPERTIES, Level.WARN, name + " : " + Format.signedPercent(gap) + " au-dessus du marché",
                        "La valeur retenue dépasse nettement la médiane des ventes du secteur : votre patrimoine est peut-être surestimé.").link(page + "/marche/ventes", "Comparer aux ventes"));
            } else if (gap != null && gap.compareTo(BigDecimal.valueOf(-15)) < 0) {
                checks.add(Check.of(key + "market-gap", Domain.PROPERTIES, Level.INFO, name + " : " + Format.signedPercent(gap) + " sous le marché",
                        "La valeur retenue est prudente par rapport aux ventes du secteur.").link(page + "/marche/ventes", "Comparer aux ventes"));
            }
            if (summary.occupancyMissing()) {
                checks.add(Check.of(key + "occupancy", Domain.PROPERTIES, Level.WARN, name + " : occupation non renseignée",
                        "Ni location ni résidence principale : l'enrichissement réel ne compte aucun loyer.").link("/administration/bien/" + summary.property().id() + "/loyers", "Renseigner l'occupation"));
            }
            BigDecimal yield = summary.returns().grossYield();
            if (yield != null) {
                Level level = yield.compareTo(BigDecimal.valueOf(3)) < 0 ? Level.WARN : yield.compareTo(BigDecimal.valueOf(5)) >= 0 ? Level.GOOD : Level.INFO;
                checks.add(Check.of(key + "yield", Domain.PROPERTIES, level, name + " : rendement brut " + Format.percent(yield),
                        "Loyer annuel ÷ coût d'acquisition. Repères : sous 3 % le placement rapporte peu, au-dessus de 5 % il est bon (avant charges et impôts)."));
            }
            if (summary.value().signum() > 0 && summary.debt().signum() > 0) {
                BigDecimal ratio = summary.debt().multiply(HUNDRED).divide(summary.value(), 0, RoundingMode.HALF_UP);
                if (ratio.compareTo(HUNDRED) > 0) {
                    checks.add(Check.of(key + "debt", Domain.PROPERTIES, Level.BAD, name + " : le prêt dépasse la valeur (" + ratio + " %)",
                            "Le capital restant dû est supérieur à la valeur retenue : une vente ne suffirait pas à rembourser."));
                } else if (ratio.compareTo(BigDecimal.valueOf(80)) > 0) {
                    checks.add(Check.of(key + "debt", Domain.PROPERTIES, Level.WARN, name + " : " + ratio + " % encore dû",
                            "Le capital restant dû représente plus de 80 % de la valeur : peu de marge si les prix baissent."));
                }
            }
            for (Portfolio.LoanSummary loan : summary.loans()) {
                Renegotiation.of(loan.status(), market).ifPresent(item -> {
                    String link = "/pret/" + loan.loan().id();
                    if (item.verdict() == Renegotiation.Verdict.WORTH) {
                        checks.add(Check.of("loan-" + loan.loan().id() + "-rate", Domain.PROPERTIES, Level.WARN, loan.loan().name() + " : taux au-dessus du marché",
                                Format.percent(item.ownRate()) + " contre " + Format.percent(item.marketRate()) + " en moyenne aujourd'hui : environ "
                                        + euroWhole(item.netSaving()) + " d'économie après pénalités. Demandez une simulation de renégociation.").link(link, "Voir le prêt"));
                    } else if (item.gap().signum() < 0) {
                        checks.add(Check.of("loan-" + loan.loan().id() + "-rate", Domain.PROPERTIES, Level.GOOD, loan.loan().name() + " : taux avantageux",
                                Format.percent(item.ownRate()) + ", sous la moyenne actuelle de " + Format.percent(item.marketRate()) + " : gardez-le."));
                    }
                });
            }
        }
        if (checks.stream().noneMatch(check -> check.level() == Level.BAD || check.level() == Level.WARN) && !overview.properties().isEmpty()) {
            checks.add(Check.of("properties-current", Domain.PROPERTIES, Level.GOOD, "Biens à jour", "Estimations récentes, occupation renseignée, prêts sans alerte."));
        }
        return checks;
    }

    // ---- Impots ----

    static List<Check> taxes(List<Income> rawIncomes, List<Forecast.Average> expenses, Portfolio.Overview overview, LocalDate today) {
        List<Check> checks = new ArrayList<>();
        boolean salaries = rawIncomes.stream().anyMatch(income -> income.kind() == IncomeKind.SALARY && (income.end() == null || !income.end().isBefore(today)));
        boolean withholding = expenses.stream().anyMatch(line -> line.category() == Category.TAXES
                && (normalize(line.label()).contains("impot sur le revenu") || normalize(line.label()).contains("prelevement")));
        if (salaries && !withholding) {
            checks.add(Check.of("withholding", Domain.TAXES, Level.WARN, "Prélèvement à la source absent",
                    "Des salaires sont saisis, mais pas l'impôt prélevé à la source : le reste à vivre est surestimé. Son montant figure sur chaque bulletin de paie.")
                    .link("/assistant/impots", "Saisir l'impôt"));
        } else if (withholding) {
            checks.add(Check.of("withholding", Domain.TAXES, Level.GOOD, "Prélèvement à la source saisi", "Vérifiez-le chaque septembre : le taux change avec l'avis d'impôt."));
        }
        if (overview == null || overview.properties().isEmpty()) return checks;

        List<Portfolio.TaxYear> years = overview.propertyTaxes();
        if (!years.isEmpty()) {
            Portfolio.TaxYear latest = years.getFirst();
            if (latest.year() == today.getYear() && latest.entered() == 0 && today.getMonthValue() >= 10) {
                checks.add(Check.of("property-tax-estimated-" + latest.year(), Domain.TAXES, Level.INFO, "Taxe foncière " + latest.year() + " estimée",
                        "L'avis d'octobre est arrivé : saisissez le montant réel (" + euroWhole(latest.amount()) + " estimés).").link("/assistant/revue-annuelle#biens", "Saisir la taxe foncière"));
            }
            Portfolio.TaxYear lastEntered = years.stream().filter(year -> year.estimated() == 0 && year.evolution() != null && !year.newProperty()).findFirst().orElse(null);
            if (lastEntered != null && lastEntered.evolution().compareTo(BigDecimal.TEN) > 0) {
                checks.add(Check.of("property-tax-increase-" + lastEntered.year(), Domain.TAXES, Level.WARN, "Taxe foncière " + lastEntered.year() + " : " + Format.signedPercent(lastEntered.evolution()),
                        "Hausse nettement au-dessus de la revalorisation légale (environ 4 % ces dernières années) : vérifiez l'avis, un changement de taux voté par la commune "
                                + "ou une erreur de surface est possible."));
            }
        }

        BigDecimal rents = overview.properties().stream().map(summary -> summary.returns().monthlyRent()).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(BigDecimal.valueOf(12));
        if (rents.signum() > 0) {
            boolean above = rents.compareTo(MICRO_FONCIER) > 0;
            checks.add(Check.of("rental-income", Domain.TAXES, above ? Level.WARN : Level.INFO, "Loyers perçus : " + euroWhole(rents) + " par an",
                    above ? "Au-delà de 15 000 € par an, le micro-foncier n'est plus possible : déclaration au régime réel (formulaire 2044), qui déduit charges et intérêts."
                            : "À déclarer chaque année. Jusqu'à 15 000 € : micro-foncier, abattement de 30 % ; le régime réel peut être plus avantageux si les charges et intérêts dépassent 30 %."));
        }

        BigDecimal base = BigDecimal.ZERO;
        for (Portfolio.PropertySummary summary : overview.properties()) {
            BigDecimal value = summary.returns().isPrincipalResidence() ? summary.value().multiply(new BigDecimal("0.7")) : summary.value();
            base = base.add(value).subtract(summary.debt());
        }
        if (base.compareTo(IFI_THRESHOLD) >= 0) {
            checks.add(Check.of("wealth-tax", Domain.TAXES, Level.WARN, "Impôt sur la fortune immobilière probable",
                    "Patrimoine immobilier net estimé à " + euroWhole(base) + " (résidence principale abattue de 30 %, prêts déduits), au-dessus du seuil de 1,3 million : "
                            + "déclaration IFI à vérifier avec un conseiller."));
        } else if (base.compareTo(new BigDecimal("1100000")) >= 0) {
            checks.add(Check.of("wealth-tax", Domain.TAXES, Level.INFO, "Proche du seuil de l'IFI",
                    "Patrimoine immobilier net estimé à " + euroWhole(base) + ", près du seuil de 1,3 million."));
        }
        return checks;
    }

    private static BigDecimal sum(List<Forecast.Average> lines) {
        return lines.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
