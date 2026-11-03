package fr.patria.household;

import fr.patria.common.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Repartition d'une depense, d'un bien ou d'un pret entre les personnes du foyer, en pourcentages entiers.
 * Une repartition vide signifie « a parts egales » entre toutes les personnes, y compris celles ajoutees plus tard.
 */
public final class Shares {
    /** Element reparti ; le code est stocke en base. */
    public enum Subject { BUDGET_ITEM, PROPERTY, LOAN }

    public record Key(Subject subject, long id) {
    }

    private Shares() {
    }

    /**
     * Part d'une personne, de 0 a 1. Les pourcentages sont rapportes a leur somme sur les personnes encore
     * presentes : supprimer une personne redistribue sa part aux autres.
     */
    public static BigDecimal weight(Map<Long, Integer> custom, List<Person> people, long personId) {
        if (people.stream().noneMatch(person -> person.id() == personId)) return BigDecimal.ZERO;
        int sum = people.stream().mapToInt(person -> custom.getOrDefault(person.id(), 0)).sum();
        if (sum == 0) return BigDecimal.ONE.divide(BigDecimal.valueOf(people.size()), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(custom.getOrDefault(personId, 0)).divide(BigDecimal.valueOf(sum), 6, RoundingMode.HALF_UP);
    }

    public static boolean isEqual(Map<Long, Integer> custom, List<Person> people) {
        return people.stream().mapToInt(person -> custom.getOrDefault(person.id(), 0)).sum() == 0;
    }

    /** « Parts égales », « Alice 100 % » ou « Alice 60 % · Bob 40 % ». Vide avec une seule personne. */
    public static String describe(Map<Long, Integer> custom, List<Person> people) {
        if (people.size() < 2) return "";
        if (isEqual(custom, people)) return "Parts égales";
        List<Person> sharing = people.stream().filter(person -> custom.getOrDefault(person.id(), 0) > 0).toList();
        return sharing.stream().map(person -> person.name() + " " + percent(weight(custom, people, person.id())))
                .collect(Collectors.joining(" · "));
    }

    /** Pourcentage arrondi a l'unite : « 50 % ». */
    public static String percent(BigDecimal weight) {
        return weight.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + " %";
    }

    /** Nom du champ de formulaire de la part d'une personne. */
    public static String field(Person person) {
        return "share_" + person.id();
    }

    /** Valeurs de formulaire d'une repartition enregistree (champs vides pour « parts egales »). */
    public static Map<String, String> values(Map<Long, Integer> custom, List<Person> people) {
        Map<String, String> values = new LinkedHashMap<>();
        if (isEqual(custom, people)) return values;
        for (Person person : people) values.put(field(person), String.valueOf(custom.getOrDefault(person.id(), 0)));
        return values;
    }

    /**
     * Lit la repartition saisie : tous les champs vides = parts egales (repartition vide) ; sinon des entiers de
     * 0 a 100, un champ vide valant 0, dont la somme fait 100.
     */
    public static Map<Long, Integer> parse(Map<String, String> form, List<Person> people) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (people.size() < 2) return result;
        boolean any = people.stream().anyMatch(person -> !form.getOrDefault(field(person), "").isBlank());
        if (!any) return result;
        int sum = 0;
        for (Person person : people) {
            String raw = form.getOrDefault(field(person), "").trim().replace("%", "").trim();
            int value;
            try {
                value = raw.isEmpty() ? 0 : Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw new ValidationException("Part de " + person.name() + " : nombre entier attendu, de 0 à 100.");
            }
            if (value < 0 || value > 100) throw new ValidationException("Part de " + person.name() + " : de 0 à 100 %.");
            result.put(person.id(), value);
            sum += value;
        }
        if (sum != 100) throw new ValidationException("La répartition doit faire 100 % au total (actuellement " + sum + " %).");
        return result;
    }
}
