package fr.patria.account;

import fr.patria.web.Viewer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Connexion precedente d'un compte et echecs depuis, pour l'en-tete et « Mon compte ». */
public final class LoginHistory {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private LoginHistory() {
    }

    /**
     * {@code login} : identifiant du compte ; {@code sessionStart} : debut de la session en cours (sa propre connexion
     * n'est pas « la derniere »).
     */
    public static Viewer.Recent recent(AccountRepository accounts, IpLocator locator, String login, LocalDateTime sessionStart) {
        List<AccountRepository.LoginEvent> events = accounts.loginEventsOf(login, sessionStart.minusSeconds(1), 50);
        AccountRepository.LoginEvent previous = events.stream().filter(AccountRepository.LoginEvent::success).findFirst().orElse(null);
        int failures = (int) events.stream().filter(event -> !event.success() && (previous == null || event.at().isAfter(previous.at()))).count();
        if (previous == null) return new Viewer.Recent("première connexion", failures);
        String place = locator.describe(previous.ip());
        boolean known = !place.isEmpty() && !place.startsWith("Localisation");
        return new Viewer.Recent(when(previous.at(), sessionStart) + (known ? " · " + place : ""), failures);
    }

    /** « aujourd'hui 09:14 », « hier 21:12 », « 02/11/2026 18:40 ». */
    static String when(LocalDateTime at, LocalDateTime now) {
        if (at.toLocalDate().equals(now.toLocalDate())) return "aujourd'hui " + at.format(TIME);
        if (at.toLocalDate().equals(now.toLocalDate().minusDays(1))) return "hier " + at.format(TIME);
        return at.format(DAY);
    }
}
