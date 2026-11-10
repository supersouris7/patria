// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.account;

import fr.patria.web.Viewer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phrases de l'icone « qui voit quoi » : pour le foyer affiche, quels comptes voient quelles informations. Le texte
 * dit « vous » pour la personne connectee. Fonctions pures.
 */
public final class AudienceText {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private AudienceText() {
    }

    /**
     * {@code accesses} : comptes actifs du foyer et leurs droits ; {@code members} : membres du foyer ; {@code me} :
     * compte connecte ; {@code changes} : changements d'acces depuis sa derniere lecture.
     */
    public static Viewer.Audience of(Map<User, Access> accesses, List<HouseholdDirectory.Member> members, long me, List<SecurityLog.AuditEntry> changes) {
        List<String> lines = new ArrayList<>();
        Access mine = accesses.entrySet().stream().filter(entry -> entry.getKey().id() == me).map(Map.Entry::getValue).findFirst().orElse(null);
        List<User> totals = users(accesses, Access::wholeHousehold);
        if (!totals.isEmpty()) lines.add(sentence(totals, me, "voit", "voyez", "voient") + " les totaux du foyer (les lignes des membres non accessibles y sont masquées).");
        for (HouseholdDirectory.Member member : members) {
            if (mine == null || !mine.members().contains(member.id())) continue;
            List<User> seeing = users(accesses, access -> access.members().contains(member.id()));
            String what = member.account() ? "du compte joint « " + member.name() + " »" : "de " + member.name();
            if (seeing.size() == 1 && seeing.getFirst().id() == me) lines.add("Seul vous pouvez voir et modifier les informations " + what + ".");
            else lines.add(sentence(seeing, me, "a", "avez", "ont") + " accès aux informations " + what + ".");
        }
        List<User> estate = users(accesses, Access::realEstate);
        if (!estate.isEmpty() && mine != null && mine.realEstate()) {
            lines.add("Les biens de ce foyer (tous, avec leurs prêts et charges) : " + lower(sentence(estate, me, "les gère", "les gérez", "les gèrent")) + ".");
        }
        List<String> recent = changes.stream().map(change -> change.at().format(DAY) + " · " + change.detail()).toList();
        return new Viewer.Audience(lines, recent);
    }

    private static List<User> users(Map<User, Access> accesses, java.util.function.Predicate<Access> filter) {
        return accesses.entrySet().stream().filter(entry -> filter.test(entry.getValue())).map(Map.Entry::getKey).toList();
    }

    /** « Paul et vous avez », « Paul gère », « Vous gérez », « Paul, Jules et Camille gèrent ». */
    static String sentence(List<User> users, long me, String one, String withMe, String many) {
        boolean includesMe = users.stream().anyMatch(user -> user.id() == me);
        List<String> names = new ArrayList<>(users.stream().filter(user -> user.id() != me).map(User::name).toList());
        if (includesMe) names.add(names.isEmpty() ? "Vous" : "vous");
        String joined = names.size() == 1 ? names.getFirst() : String.join(", ", names.subList(0, names.size() - 1)) + " et " + names.getLast();
        String verb = includesMe ? withMe : names.size() == 1 ? one : many;
        return joined + " " + verb;
    }

    private static String lower(String text) {
        return text.startsWith("Vous") ? "v" + text.substring(1) : text;
    }

    /** Pour le journal : ce qu'un compte voit dans un foyer, en quelques mots. */
    public static String summary(Access access, List<HouseholdDirectory.Member> members) {
        if (access == null) return "plus d'accès";
        List<String> parts = new ArrayList<>();
        if (access.wholeHousehold()) parts.add("totaux du foyer");
        members.stream().filter(member -> access.members().contains(member.id())).forEach(member -> parts.add(member.name()));
        if (access.realEstate()) parts.add("tous les biens du foyer");
        return parts.isEmpty() ? "accès" : String.join(", ", parts);
    }
}
