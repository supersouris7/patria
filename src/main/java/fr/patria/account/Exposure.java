package fr.patria.account;

import fr.patria.web.Request;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Patria est-il accessible depuis Internet ? Oui s'il est ouvert par un nom de domaine (pas localhost, ni une
 * adresse ou un nom du reseau local), ou si une connexion a deja reussi depuis une adresse IP publique. Sert a
 * recommander la double authentification a l'administrateur.
 */
public final class Exposure {
    private static final Pattern PRIVATE_HOST = Pattern.compile(
            "localhost|127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|\\[?::1]?|\\[?f[cd][0-9a-f:]*]?"
                    + "|[^.]+|.+\\.(local|lan|home|internal|home\\.arpa|localdomain)");

    private Exposure() {
    }

    public static boolean exposed(Request request, AccountRepository accounts) {
        String host = request.header("Host");
        String name = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        if (!name.isEmpty() && !PRIVATE_HOST.matcher(name).matches()) return true;
        return accounts.loginEvents(true, 200).stream().anyMatch(event -> !IpLocator.isLocal(event.ip()));
    }
}
