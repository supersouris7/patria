package fr.patria.account;

import fr.patria.web.Viewer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static Viewer.Audience of(Map<User, Access> accesses, List<HouseholdDirectory.Member> members, long me, List<AccountRepository.AuditEntry> changes) {
        List<String> lines = new ArrayList<>();
        Access mine = accesses.entrySet().stream().filter(entry -> entry.getKey().id() == me).map(Map.Entry::getValue).findFirst().orElse(null);
        List<User> managers = users(accesses, access -> access.manager());
        List<User> totals = users(accesses, access -> !access.manager() && access.wholeHousehold());
        if (!managers.isEmpty()) lines.add(sentence(managers, me, "gère", "gérez", "gèrent") + " tout le foyer : toutes les lignes, les biens et les prêts.");
        if (!totals.isEmpty()) lines.add(sentence(totals, me, "voit", "voyez", "voient") + " les totaux du foyer et toutes ses lignes, en lecture.");
        for (HouseholdDirectory.Member member : members) {
            boolean visible = mine != null && (mine.manager() || mine.wholeHousehold() || mine.members().contains(member.id()));
            if (!visible) continue;
            Set<User> seeing = new LinkedHashSet<>(users(accesses, access -> access.manager() || access.wholeHousehold() || access.members().contains(member.id())));
            String what = member.account() ? "du compte joint « " + member.name() + " »" : "de " + member.name();
            if (seeing.size() == 1 && seeing.iterator().next().id() == me) lines.add("Seul vous pouvez voir les informations " + what + ".");
            else lines.add(sentence(List.copyOf(seeing), me, "a", "avez", "ont") + " accès aux informations " + what + ".");
        }
        List<User> estate = users(accesses, access -> access.manager() || access.realEstate());
        if (!estate.isEmpty() && mine != null && (mine.manager() || mine.realEstate())) {
            lines.add("Immobilier : " + lower(sentence(estate, me, "a", "avez", "ont")) + " accès.");
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
        if (access.manager()) return "gestionnaire de tout le foyer";
        List<String> parts = new ArrayList<>();
        if (access.wholeHousehold()) parts.add("totaux du foyer");
        members.stream().filter(member -> access.members().contains(member.id())).forEach(member -> parts.add(member.name()));
        if (access.realEstate()) parts.add("immobilier");
        return parts.isEmpty() ? "accès" : String.join(", ", parts);
    }
}
