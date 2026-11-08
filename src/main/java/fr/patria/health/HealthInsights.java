package fr.patria.health;

import static fr.patria.ui.Format.euroWhole;

import fr.patria.budget.Category;
import fr.patria.budget.Forecast;
import fr.patria.common.Frequency;
import fr.patria.health.Check.Domain;
import fr.patria.health.Check.Level;
import fr.patria.ui.Format;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Analyses avancees du bilan, en fonctions pures : grosses sommes a mensualiser, mois le plus serre, couverture du
 * compte joint par les virements, abonnements en double.
 */
final class HealthInsights {
    /** Au-dela, un paiement non mensuel pese sur son mois. */
    static final BigDecimal LARGE = new BigDecimal("300");

    private HealthInsights() {
    }

    /**
     * Paiements non mensuels d'au moins {@value #LARGE} € sur les 12 prochains mois : impots, taxe fonciere, assurances
     * qui se mensualisent. A surveiller s'ils font passer leur mois en deficit.
     */
    static List<Check> largeSums(Forecast.View view) {
        List<Check> checks = new ArrayList<>();
        for (Forecast.Month month : view.months()) {
            for (Forecast.Line line : month.expenses()) {
                if (line.frequency() == Frequency.MONTHLY || line.amount().compareTo(LARGE) < 0 || line.category() == Category.TRANSFER) continue;
                boolean deficit = month.balance().signum() < 0;
                String how = switch (line.category()) {
                    case TAXES -> "L'impôt et la taxe foncière se mensualisent sur impots.gouv.fr (espace particulier, « Gérer mes contrats de prélèvement »).";
                    case INSURANCE, HEALTH -> "La plupart des assureurs proposent un paiement mensuel, souvent sans frais : demandez-le.";
                    case HOUSING -> "Charges de copropriété : un appel trimestriel se provisionne mois par mois.";
                    default -> "Si ce paiement peut se faire en plusieurs fois, demandez-le ; sinon mettez la somme de côté chaque mois.";
                };
                BigDecimal monthly = line.amount().divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
                checks.add(Check.of("large-" + month.month() + "-" + line.label(), Domain.FINANCES, deficit ? Level.WARN : Level.INFO,
                        euroWhole(line.amount()) + " en " + Format.month(month.month()).toLowerCase(Locale.FRENCH) + " : " + line.label(),
                        (deficit ? "Ce mois passe en déficit (" + euroWhole(month.balance()) + "). " : "")
                                + "Mensualisé, cela ferait environ " + euroWhole(monthly) + " par mois. " + how)
                        .link(line.link() == null ? "/" : line.link(), line.link() == null ? "Voir le prévisionnel" : "Voir la ligne"));
            }
        }
        // Les plus grosses d'abord, au plus 5 pour garder le bilan lisible.
        checks.sort(Comparator.comparing((Check check) -> check.level() == Level.WARN ? 0 : 1));
        return checks.size() > 5 ? checks.subList(0, 5) : checks;
    }

    /** Mois le plus serre des 12 prochains : le reste a vivre le plus faible, et ce qui le creuse. */
    static List<Check> tightestMonth(Forecast.View view) {
        if (view.months().isEmpty() || !view.hasIncome()) return List.of();
        Forecast.Month tight = view.months().stream().min(Comparator.comparing(Forecast.Month::balance)).orElseThrow();
        BigDecimal average = view.months().stream().map(Forecast.Month::balance).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(view.months().size()), 0, RoundingMode.HALF_UP);
        if (tight.balance().compareTo(average) >= 0) return List.of();
        String biggest = tight.expenses().stream().filter(line -> line.frequency() != Frequency.MONTHLY).max(Comparator.comparing(Forecast.Line::amount))
                .map(line -> " Principalement : " + line.label() + " (" + euroWhole(line.amount()) + ").").orElse("");
        boolean negative = tight.balance().signum() < 0;
        return List.of(Check.of("tightest-month", Domain.FINANCES, negative ? Level.WARN : Level.INFO,
                "Mois le plus serré : " + Format.month(tight.month()) + " (" + euroWhole(tight.balance()) + ")",
                "Le reste à vivre moyen est de " + euroWhole(average) + " ; ce mois-là il " + (negative ? "devient négatif." : "baisse nettement.") + biggest)
                .link("/", "Voir le prévisionnel"));
    }

    /**
     * Compte joint : les virements recus couvrent-ils ses depenses chaque mois ? Le pire mois et ce qu'il faudrait
     * ajouter par mois pour l'equilibrer.
     */
    static List<Check> jointAccount(String name, Forecast.View account) {
        if (account.months().isEmpty() || !account.hasExpenses()) return List.of();
        Forecast.Month worst = account.months().stream().min(Comparator.comparing(Forecast.Month::balance)).orElseThrow();
        if (worst.balance().signum() >= 0) {
            return List.of(Check.of("joint-" + name, Domain.FINANCES, Level.GOOD, name + " : les virements couvrent les dépenses chaque mois",
                    "Sur les 12 prochains mois, ce qui arrive sur « " + name + " » suffit à payer ce qui en part.").link("/administration/virements", "Voir les virements"));
        }
        long short_ = account.months().stream().filter(month -> month.balance().signum() < 0).count();
        BigDecimal missing = account.months().stream().map(Forecast.Month::balance).filter(balance -> balance.signum() < 0).map(BigDecimal::negate)
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
        return List.of(Check.of("joint-" + name, Domain.FINANCES, short_ >= 3 ? Level.BAD : Level.WARN,
                name + " : " + Format.plural(short_, "mois à découvert", "mois à découvert") + " sur 12",
                "Le pire : " + Format.month(worst.month()) + ", il manque " + euroWhole(worst.balance().negate()) + ". Ajouter environ "
                        + euroWhole(missing) + " par mois aux virements équilibrerait l'année.").link("/administration/virements", "Ajuster les virements"));
    }

    /** Familles d'abonnements qui font souvent doublon, reconnues dans le libelle. */
    private static final Map<String, List<String>> FAMILIES = new LinkedHashMap<>();

    static {
        FAMILIES.put("de vidéo à la demande", List.of("netflix", "disney", "prime video", "canal", "paramount", "apple tv", "molotov", "ocs", "max"));
        FAMILIES.put("de musique", List.of("spotify", "deezer", "apple music", "youtube music", "qobuz", "tidal"));
        FAMILIES.put("de téléphone mobile", List.of("forfait", "mobile", "sosh", "free mobile", "red by", "b&you", "bouygues", "sfr", "orange"));
        FAMILIES.put("de box internet", List.of("box", "fibre", "internet", "livebox", "freebox", "bbox"));
        FAMILIES.put("de stockage en ligne", List.of("icloud", "google one", "dropbox", "onedrive"));
    }

    /**
     * Abonnements de la meme famille en double (deux services de video, deux box…) parmi les depenses en cours, avec
     * leur cout annuel.
     */
    static List<Check> duplicates(List<Forecast.Average> expenses) {
        List<Check> checks = new ArrayList<>();
        List<Forecast.Average> recurring = expenses.stream().filter(line -> line.frequency().isRecurring() && line.origin() == Forecast.Origin.BUDGET).toList();
        for (Map.Entry<String, List<String>> family : FAMILIES.entrySet()) {
            List<Forecast.Average> found = recurring.stream().filter(line -> family.getValue().stream().anyMatch(word -> matches(line.label(), word))).toList();
            if (found.size() < 2) continue;
            BigDecimal yearly = found.stream().map(Forecast.Average::monthly).reduce(BigDecimal.ZERO, BigDecimal::add).multiply(BigDecimal.valueOf(12));
            checks.add(Check.of("duplicate-" + family.getKey(), Domain.FINANCES, Level.WARN,
                    found.size() + " abonnements " + family.getKey() + " : " + String.join(", ", found.stream().map(Forecast.Average::label).toList()),
                    euroWhole(yearly) + " par an au total. Gardez-vous les deux ? Une résiliation se note dans la revue annuelle (« Arrêtée »).")
                    .link("/assistant/revue-annuelle", "Faire le tri"));
        }
        return checks;
    }

    private static boolean matches(String label, String word) {
        String text = " " + normalize(label) + " ";
        return text.contains(" " + normalize(word) + " ") || (word.length() > 4 && text.contains(normalize(word)));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9& ]", " ").trim();
    }
}
