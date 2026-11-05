package fr.patria.account;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pays et ville d'une adresse IP du journal des connexions (page Securite), demandes a ipwho.is (HTTPS, sans cle),
 * une seule fois par adresse, en arriere-plan, et gardes dans la base des comptes. Seule l'adresse IP est envoyee.
 * Adresses locales et mode hors ligne : aucune requete.
 */
public final class IpLocator implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(IpLocator.class.getName());
    private static final Pattern FIELD = Pattern.compile("\"(success|country|city)\"\\s*:\\s*(true|false|\"((?:[^\"\\\\]|\\\\.)*)\")");
    /** Adresse IPv4, ou IPv6 (avec « : ») : jamais un nom de machine, qui declencherait une requete DNS. */
    private static final Pattern ADDRESS = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F.]*:[0-9a-fA-F:.]{1,44}");
    private final SecurityLog log;
    private final boolean offline;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "localisation-ip");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public IpLocator(SecurityLog log, boolean offline, Clock clock) {
        this.log = log;
        this.offline = offline;
        this.clock = clock;
    }

    /** « Lyon, France », « Réseau local », « Localisation en cours… » ou vide (hors ligne, inconnue). */
    public String describe(String ip) {
        if (isLocal(ip)) return "Réseau local";
        java.util.Optional<String[]> known = log.ipLocation(ip);
        if (known.isPresent()) {
            String[] place = known.get();
            if (place[0].isEmpty()) return "Inconnue";
            return place[1].isEmpty() ? place[0] : place[1] + ", " + place[0];
        }
        if (offline) return "";
        lookUp(ip);
        return "Localisation en cours…";
    }

    /** Demande la localisation en arriere-plan, si elle n'est pas deja connue. */
    public void lookUp(String ip) {
        if (offline || isLocal(ip) || !ADDRESS.matcher(ip).matches() || log.ipLocation(ip).isPresent() || !pending.add(ip)) return;
        worker.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://ipwho.is/" + ip + "?lang=fr&fields=success,country,city"))
                        .timeout(Duration.ofSeconds(6)).header("Accept", "application/json").GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String country = "";
                String city = "";
                boolean success = false;
                Matcher matcher = FIELD.matcher(response.body());
                while (matcher.find()) {
                    switch (matcher.group(1)) {
                        case "success" -> success = "true".equals(matcher.group(2));
                        case "country" -> country = clean(matcher.group(3));
                        default -> city = clean(matcher.group(3));
                    }
                }
                log.saveIpLocation(ip, success ? country : "", success ? city : "", LocalDate.now(clock));
            } catch (Exception exception) {
                // Reseau indisponible : nouvel essai a la prochaine visite de la page.
                LOG.fine("Localisation IP impossible : " + exception.getMessage());
            } finally {
                pending.remove(ip);
            }
        });
    }

    private static String clean(String value) {
        if (value == null) return "";
        String text = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(value)
                .replaceAll(match -> Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(match.group(1), 16))))
                .replace("\\\"", "\"").replaceAll("[\\p{Cntrl}<>]", "");
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    /** Adresse du reseau local, du poste lui-meme ou illisible : rien a localiser. */
    static boolean isLocal(String ip) {
        if (ip == null || !ADDRESS.matcher(ip).matches()) return true;
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc);
        } catch (java.net.UnknownHostException | IllegalArgumentException exception) {
            return true;
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
