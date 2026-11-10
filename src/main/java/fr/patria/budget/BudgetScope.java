// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.budget;

import fr.patria.common.ForbiddenException;
import fr.patria.household.Person;
import fr.patria.household.Visibility;
import java.util.List;

/**
 * Vue du budget d'une personne connectee : la personne demandee si sa vue est ouverte, sinon le foyer (si ouvert) ou
 * la premiere vue ouverte ; le selecteur ne propose que les vues ouvertes. Partage par le budget et le bilan de sante.
 */
public final class BudgetScope {
    private BudgetScope() {
    }

    public static Forecast.View view(Forecast forecast, Visibility visibility, Long asked) {
        if (visibility.seesEverything()) return forecast.next12Months(asked);
        List<Person> open = visibility.people();
        Long scope;
        if (asked != null && open.stream().anyMatch(person -> person.id() == asked)) scope = asked;
        else if (visibility.wholeHousehold()) scope = null;
        else if (!open.isEmpty()) scope = open.getFirst().id();
        else throw new ForbiddenException("Aucune vue ne vous est ouverte dans ce foyer.");
        return forecast.next12Months(scope, visibility.hidden()).withTabs(visibility.wholeHousehold(), open);
    }
}
