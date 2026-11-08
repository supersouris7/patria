package fr.patria.savings;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Donnees de marche partagees par tous les foyers, jamais bloquantes : la premiere demande lance le telechargement en
 * arriere-plan et renvoie vide en attendant ; une panne garde la derniere valeur connue. Aucun appel en mode hors ligne.
 * <ul>
 *   <li>cours de bourse : Yahoo Finance (endpoint « chart », sans cle), rafraichi toutes les heures ;</li>
 *   <li>inflation sur 12 mois : INSEE, indice des prix a la consommation base 2025, ensemble des menages, France
 *   (serie BDM 011814630, sans cle), rafraichie une fois par jour (la statistique est mensuelle).</li>
 * </ul>
 */
public final class MarketFeeds {
    static final String INFLATION_URL = "https://api.insee.fr/series/BDM/V1/data/SERIES_BDM/011814630?lastNObservations=13";
    static final String QUOTE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?range=1d&interval=1d";
    private static final Duration QUOTE_REFRESH = Duration.ofHours(1);
    private static final Duration INFLATION_REFRESH = Duration.ofDays(1);
    private static final Duration RETRY = Duration.ofMinutes(30);
    private static final Logger LOG = Logger.getLogger(MarketFeeds.class.getName());
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.^=-]{1,20}");

    /** Cours d'une valeur et sa devise. */
    public record Quote(BigDecimal price, String currency) {
    }

    /** Inflation sur 12 mois (en %) au mois {@code month}, d'apres l'indice des prix INSEE. */
    public record Inflation(BigDecimal percent, YearMonth month) {
    }

    /** Telechargement d'une URL ; remplace en test. */
    @FunctionalInterface
    interface Source {
        String fetch(String url) throws IOException, InterruptedException;
    }

    private final Source source;
    private final Clock clock;
    private final Executor background;
    private final boolean offline;
    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Map<String, Long> nextQuote = new ConcurrentHashMap<>();
    private final Map<String, Boolean> fetchingQuote = new ConcurrentHashMap<>();
    private final AtomicBoolean fetchingInflation = new AtomicBoolean();
    private volatile Inflation inflation;
    private volatile long nextInflation;

    public MarketFeeds(Clock clock, boolean offline) {
        this(MarketFeeds::download, clock, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "patria-marches");
            thread.setDaemon(true);
            return thread;
        }), offline);
    }

    MarketFeeds(Source source, Clock clock, Executor background, boolean offline) {
        this.source = source;
        this.clock = clock;
        this.background = background;
        this.offline = offline;
    }

    public boolean offline() {
        return offline;
    }

    /** Dernier cours connu de {@code symbol} ; vide tant qu'il n'a pas pu etre telecharge (ou hors ligne). */
    public Optional<Quote> quote(String symbol) {
        if (offline || symbol == null || !SYMBOL.matcher(symbol).matches()) return Optional.empty();
        String key = symbol.toUpperCase(java.util.Locale.ROOT);
        if (clock.millis() >= nextQuote.getOrDefault(key, 0L) && fetchingQuote.putIfAbsent(key, true) == null) {
            background.execute(() -> {
                try {
                    quotes.put(key, parseQuote(source.fetch(QUOTE_URL.formatted(URLEncoder.encode(key, StandardCharsets.UTF_8)))));
                    nextQuote.put(key, clock.millis() + QUOTE_REFRESH.toMillis());
                } catch (IOException | RuntimeException exception) {
                    LOG.log(Level.INFO, "Cours de " + key + " indisponible, nouvel essai plus tard", exception);
                    nextQuote.put(key, clock.millis() + RETRY.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    fetchingQuote.remove(key);
                }
            });
        }
        return Optional.ofNullable(quotes.get(key));
    }

    /** Derniere inflation connue ; vide tant qu'elle n'a pas pu etre telechargee (ou hors ligne). */
    public Optional<Inflation> inflation() {
        if (offline) return Optional.empty();
        if (clock.millis() >= nextInflation && fetchingInflation.compareAndSet(false, true)) {
            background.execute(() -> {
                try {
                    inflation = parseInflation(source.fetch(INFLATION_URL));
                    nextInflation = clock.millis() + INFLATION_REFRESH.toMillis();
                } catch (IOException | RuntimeException exception) {
                    LOG.log(Level.WARNING, "Inflation (INSEE) indisponible, nouvel essai plus tard", exception);
                    nextInflation = clock.millis() + RETRY.toMillis();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    fetchingInflation.set(false);
                }
            });
        }
        return Optional.ofNullable(inflation);
    }

    /** {@code chart.result[0].meta.regularMarketPrice} et {@code currency} de la reponse Yahoo. */
    static Quote parseQuote(String json) {
        Matcher price = Pattern.compile("\"regularMarketPrice\"\\s*:\\s*(-?[0-9.]+(?:[eE][-+]?\\d+)?)").matcher(json);
        if (!price.find()) throw new IllegalArgumentException("Cours absent de la réponse.");
        Matcher currency = Pattern.compile("\"currency\"\\s*:\\s*\"([A-Za-z]{3})\"").matcher(json);
        BigDecimal value = new BigDecimal(price.group(1));
        if (value.signum() <= 0) throw new IllegalArgumentException("Cours invraisemblable : " + value);
        return new Quote(value, currency.find() ? currency.group(1).toUpperCase(java.util.Locale.ROOT) : "EUR");
    }

    /** Glissement annuel : dernier indice / indice du meme mois un an plus tot − 1 (observations SDMX de l'INSEE). */
    static Inflation parseInflation(String xml) {
        Matcher obs = Pattern.compile("<Obs [^>]*TIME_PERIOD=\"(\\d{4}-\\d{2})\"[^>]*OBS_VALUE=\"([0-9.]+)\"").matcher(xml);
        Map<YearMonth, BigDecimal> values = new java.util.TreeMap<>();
        while (obs.find()) values.put(YearMonth.parse(obs.group(1)), new BigDecimal(obs.group(2)));
        if (values.isEmpty()) throw new IllegalArgumentException("Aucune observation INSEE.");
        YearMonth last = ((java.util.TreeMap<YearMonth, BigDecimal>) values).lastKey();
        BigDecimal before = values.get(last.minusYears(1));
        if (before == null || before.signum() <= 0) throw new IllegalArgumentException("Indice d'il y a un an absent.");
        BigDecimal percent = values.get(last).divide(before, 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).movePointRight(2).setScale(1, RoundingMode.HALF_UP);
        if (percent.abs().compareTo(BigDecimal.valueOf(30)) > 0) throw new IllegalArgumentException("Inflation invraisemblable : " + percent);
        return new Inflation(percent, last);
    }

    private static String download(String url) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Patria").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(url + " : HTTP " + response.statusCode());
        return response.body();
    }
}
