package fr.patria.household;

import fr.patria.common.ForbiddenException;
import fr.patria.common.ValidationException;
import fr.patria.web.Viewer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ce qu'une personne connectee voit et modifie dans le foyer : l'administrateur tout ; un utilisateur les lignes
 * de ses vues ouvertes. Une ligne est visible si l'un de ses membres a sa vue ouverte (toutes avec la vue « Foyer »),
 * modifiable seulement si tous ses membres l'ont.
 */
public final class Visibility {
    private final Viewer viewer;
    private final List<Person> people;

    private Visibility(Viewer viewer, List<Person> people) {
        this.viewer = viewer;
        this.people = people;
    }

    public static Visibility of(Viewer viewer, List<Person> people) {
        return new Visibility(viewer, people);
    }

    public boolean manages() {
        return viewer.manages();
    }

    public boolean wholeHousehold() {
        return viewer.wholeHousehold();
    }

    /** Tous les membres du foyer. */
    public List<Person> everyone() {
        return people;
    }

    /** Membres dont la vue est ouverte : choix proposes dans les formulaires. */
    public List<Person> people() {
        return people.stream().filter(person -> viewer.sees(person.id())).toList();
    }

    public boolean sees(long personId) {
        return viewer.sees(personId);
    }

    /** Membres qui ont une part d'une repartition. */
    public Set<Long> holders(Map<Long, Integer> shares) {
        return people.stream().filter(person -> Shares.weight(shares, people, person.id()).signum() > 0).map(Person::id).collect(Collectors.toSet());
    }

    public boolean canSee(Map<Long, Integer> shares) {
        return viewer.wholeHousehold() || viewer.seesAny(holders(shares));
    }

    public boolean canEdit(Map<Long, Integer> shares) {
        return viewer.seesAll(holders(shares));
    }

    public boolean canSeeAny(long... personIds) {
        for (long id : personIds) if (viewer.sees(id)) return true;
        return viewer.wholeHousehold();
    }

    public boolean canEditAll(long... personIds) {
        for (long id : personIds) if (!viewer.sees(id)) return false;
        return true;
    }

    /** Refuse la modification d'une ligne qui concerne un membre dont la vue n'est pas ouverte. */
    public void requireEdit(boolean editable) {
        if (!editable) throw new ForbiddenException("Cette ligne concerne aussi un membre dont la vue ne vous est pas ouverte : elle est en lecture seule.");
    }

    /**
     * Repartition saisie par un utilisateur, qui ne voit que les parts de ses membres : « parts egales » devient
     * des parts egales entre ses membres (personnes de preference), pas entre tous ceux du foyer.
     */
    public Map<Long, Integer> normalize(Map<Long, Integer> parsed) {
        List<Person> open = people();
        if (viewer.manages() || open.size() == people.size()) return parsed;
        if (open.isEmpty()) throw new ValidationException("Aucune vue de membre ne vous est ouverte : demandez à l'administrateur.");
        if (parsed.values().stream().mapToInt(Integer::intValue).sum() > 0) return parsed;
        List<Person> sharing = open.stream().filter(person -> !person.isAccount()).toList();
        if (sharing.isEmpty()) sharing = open;
        Map<Long, Integer> result = new LinkedHashMap<>();
        int base = 100 / sharing.size();
        int rest = 100 - base * sharing.size();
        for (int index = 0; index < sharing.size(); index++) result.put(sharing.get(index).id(), base + (index < rest ? 1 : 0));
        return result;
    }
}
