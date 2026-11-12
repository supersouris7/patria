// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.health;

import fr.patria.budget.HistoryRepository;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

/**
 * Progression du foyer, recalculee a partir de ce qui est saisi (rien n'est stocke, rien ne se perd) : points
 * d'experience et niveau, series de mois reussis, missions en plusieurs etapes. Prive et bienveillant : une serie
 * cassee ne retire aucun point, aucune comparaison avec d'autres foyers.
 */
final class Progression {
    /** Seuils de points et noms des niveaux. */
    static final int[] THRESHOLDS = {0, 100, 250, 450, 700, 1000, 1400, 1900};
    static final String[] LEVELS = {"Premiers pas", "Curieux", "Organisé", "Prévoyant", "Stratège", "Serein", "Expert", "Maître des finances"};

    /** Niveau atteint : numero (1 a 8), nom, points, seuil suivant ({@code null} au dernier niveau), avancement vers lui. */
    record Level(int number, String name, int xp, Integer next, int percent) {
    }

    /** Une serie de mois consecutifs ; {@code best} : la plus longue connue. */
    record Streak(String icon, String label, int current, String how) {
    }

    /** Une etape de mission, faite ou non. */
    record Step(String label, boolean done) {
    }

    /** Mission : plusieurs etapes, une recompense en points quand tout est fait. */
    record Mission(String id, String icon, String title, String goal, List<Step> steps, int reward) {
        boolean done() {
            return steps.stream().allMatch(Step::done);
        }

        int percent() {
            return (int) (100 * steps.stream().filter(Step::done).count() / steps.size());
        }
    }

    /** Ce que la progression lit dans l'etat du foyer. */
    record Facts(boolean budget, int badges, int economies, int goals, int goalsReached, boolean goalLinked, boolean goalHalf, int contracts,
                 int contractsAbove, BigDecimal cover, boolean noDeficit, int score, SortedMap<YearMonth, HistoryRepository.Snapshot> snapshots,
                 SortedMap<YearMonth, BigDecimal> imported, YearMonth now) {
    }

    private Progression() {
    }

    static List<Mission> missions(Facts facts) {
        double cover = facts.cover().doubleValue();
        List<Mission> missions = new ArrayList<>();
        missions.add(new Mission("ordre", "🧹", "Mettre de l'ordre", "Un budget complet et à jour.", List.of(
                new Step("Saisir revenus et dépenses", facts.budget()),
                new Step("Importer un relevé bancaire", !facts.imported().isEmpty()),
                new Step("Aucun mois en déficit à venir", facts.noDeficit())), 80));
        missions.add(new Mission("contrats", "✂️", "Chasse aux contrats", "Payer le juste prix pour ce qui revient chaque mois.", List.of(
                new Step("3 contrats reconnus dans vos dépenses", facts.contracts() >= 3),
                new Step("Un contrat renégocié", facts.economies() >= 1),
                new Step("Plus aucun contrat au-dessus des repères", facts.contracts() > 0 && facts.contractsAbove() == 0)), 120));
        missions.add(new Mission("bouclier", "🛟", "Bouclier", "Une épargne de précaution pour les coups durs.", List.of(
                new Step("1 mois de dépenses de côté", cover >= 1), new Step("3 mois de côté", cover >= 3), new Step("6 mois de côté", cover >= 6)), 150));
        missions.add(new Mission("projet", "🎯", "Un projet", "Donner un but concret à votre épargne.", List.of(
                new Step("Créer un objectif d'épargne", facts.goals() >= 1), new Step("Le relier à un livret", facts.goalLinked()),
                new Step("L'atteindre à moitié", facts.goalHalf()), new Step("L'atteindre", facts.goalsReached() >= 1)), 120));
        return missions;
    }

    /** Points : chaque chose utile faite rapporte, et les missions terminees donnent leur recompense. */
    static int xp(Facts facts, List<Mission> missions) {
        int xp = (facts.budget() ? 50 : 0) + 30 * facts.badges() + 60 * facts.economies() + 10 * facts.goals() + 40 * facts.goalsReached()
                + 15 * facts.imported().size() + 10 * Math.min(6, (int) facts.cover().doubleValue()) + facts.score() / 2;
        xp += 10 * (int) facts.snapshots().values().stream().filter(snapshot -> !snapshot.rebuilt() && snapshot.balance().signum() >= 0).count();
        return xp + missions.stream().filter(Mission::done).mapToInt(Mission::reward).sum();
    }

    static Level level(int xp) {
        int index = 0;
        for (int candidate = 0; candidate < THRESHOLDS.length; candidate++) if (xp >= THRESHOLDS[candidate]) index = candidate;
        Integer next = index + 1 < THRESHOLDS.length ? THRESHOLDS[index + 1] : null;
        int percent = next == null ? 100 : (int) (100L * (xp - THRESHOLDS[index]) / (next - THRESHOLDS[index]));
        return new Level(index + 1, LEVELS[index], xp, next, percent);
    }

    /**
     * Series : mois consecutifs jusqu'au mois en cours (ou au precedent, s'il n'est pas encore fini) avec un releve
     * importe, sans deficit, avec une epargne en hausse.
     */
    static List<Streak> streaks(Facts facts) {
        YearMonth now = facts.now();
        int imports = run(month -> facts.imported().containsKey(month), now);
        int balanced = run(month -> facts.snapshots().containsKey(month) && facts.snapshots().get(month).balance().signum() >= 0, now);
        int rising = run(month -> {
            HistoryRepository.Snapshot current = facts.snapshots().get(month);
            HistoryRepository.Snapshot before = facts.snapshots().get(month.minusMonths(1));
            return current != null && before != null && current.savings().compareTo(before.savings()) > 0;
        }, now);
        return List.of(new Streak("📥", "Relevés importés", imports, "un relevé bancaire importé chaque mois"),
                new Streak("🧭", "Mois sans déficit", balanced, "revenus au-dessus des dépenses"),
                new Streak("📈", "Épargne en hausse", rising, "plus d'épargne qu'au mois précédent"));
    }

    /** Mois consecutifs qui remplissent la condition, en partant du mois en cours ; un mois en cours pas encore rempli ne casse rien. */
    private static int run(java.util.function.Predicate<YearMonth> ok, YearMonth now) {
        YearMonth month = ok.test(now) ? now : now.minusMonths(1);
        int count = 0;
        while (ok.test(month) && count < 120) {
            count++;
            month = month.minusMonths(1);
        }
        return count;
    }
}
