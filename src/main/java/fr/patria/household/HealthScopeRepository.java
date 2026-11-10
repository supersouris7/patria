// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.household;

import fr.patria.storage.Database;
import fr.patria.storage.Sql;
import fr.patria.web.Viewer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Comptes pris en compte dans le bilan de chaque utilisateur (table {@code health_scope}, migration v15). Par
 * defaut : les membres qu'il represente et les comptes joints qu'il voit ; reglable dans le guide et sur le Bilan.
 */
public final class HealthScopeRepository {
    private final Database database;

    public HealthScopeRepository(Database database) {
        this.database = database;
    }

    /** Membres choisis, vide si l'utilisateur n'a encore rien regle. */
    public Set<Long> chosen(long userId) {
        return new LinkedHashSet<>(database.query(connection -> Sql.list(connection, "SELECT person_id FROM health_scope WHERE user_id = ? ORDER BY person_id",
                row -> row.getLong(1), userId)));
    }

    public void save(long userId, Set<Long> people) {
        database.transaction(connection -> {
            Sql.update(connection, "DELETE FROM health_scope WHERE user_id = ?", userId);
            for (long person : people) Sql.update(connection, "INSERT INTO health_scope (user_id, person_id) VALUES (?, ?)", userId, person);
            return null;
        });
    }

    public void forgetUser(long userId) {
        database.query(connection -> Sql.update(connection, "DELETE FROM health_scope WHERE user_id = ?", userId));
    }

    /**
     * Perimetre du bilan : le choix enregistre (limite aux membres visibles), sinon par defaut les membres representes
     * et les comptes joints visibles. Vide : rien a restreindre, le bilan suit la vue du budget.
     */
    public Set<Long> scope(Viewer viewer, List<Person> people) {
        Set<Long> visible = new LinkedHashSet<>();
        people.stream().filter(person -> viewer.sees(person.id())).forEach(person -> visible.add(person.id()));
        if (viewer.userId() == null) return Set.of();
        Set<Long> chosen = chosen(viewer.userId());
        chosen.retainAll(visible);
        if (!chosen.isEmpty()) return chosen;
        // Par defaut : le membre qui porte le nom du compte (son budget personnel), et les comptes joints accessibles.
        Set<Long> result = new LinkedHashSet<>();
        people.stream().filter(person -> !person.isAccount() && person.name().equalsIgnoreCase(viewer.name().trim())).forEach(person -> result.add(person.id()));
        if (result.isEmpty()) return Set.of();
        people.stream().filter(person -> person.isAccount() && visible.contains(person.id())).forEach(person -> result.add(person.id()));
        result.retainAll(visible);
        return result;
    }

    /** Vrai si le choix vient de l'utilisateur (sinon : perimetre par defaut). */
    public boolean configured(long userId) {
        return !chosen(userId).isEmpty();
    }
}
