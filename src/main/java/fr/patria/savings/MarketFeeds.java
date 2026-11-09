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
    static final String SEARCH_URL = "https://query1.finance.yahoo.com/v1/finance/search?q=%s&quotesCount=8&newsCount=0";
    static final String HISTORY_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?range=%s&interval=%s";
    private static final Duration HISTORY_REFRESH = Duration.ofHours(6);
    private static final Duration QUOTE_REFRESH = Duration.ofHours(1);
    private static final Duration INFLATION_REFRESH = Duration.ofDays(1);
    private static final Duration RETRY = Duration.ofMinutes(30);
    private static final Logger LOG = Logger.getLogger(MarketFeeds.class.getName());
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.^=-]{1,20}");

    /** Cours d'une valeur et sa devise. */
    public record Quote(BigDecimal price, String currency) {
    }

    /** Un titre trouve par la recherche : code Yahoo, nom, place de cotation, type (ETF, action, crypto…). */
    public record Found(String symbol, String name, String exchange, String type) {
    }

    /** Periode d'un graphique de cours : 1 mois (quotidien), 1 an (quotidien), 5 ans (hebdomadaire). */
    public enum Range {
        MONTH("1mo", "1d", "1 mois"), YEAR("1y", "1d", "1 an"), FIVE_YEARS("5y", "1wk", "5 ans");

        final String range;
        final String interval;
        private final String label;

        Range(String range, String interval, String label) {
            this.range = range;
            this.interval = interval;
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** {@code 1m}, {@code 1a}, {@code 5a} ; 1 an par defaut. */
        public static Range parse(String value) {
            return "1m".equals(value) ? MONTH : "5a".equals(value) ? FIVE_YEARS : YEAR;
        }

        public String code() {
            return this == MONTH ? "1m" : this == FIVE_YEARS ? "5a" : "1a";
        }
    }

    /** Historique de cours : dates et cloture dans la devise de cotation (pence ramenes en livres). */
    public record History(java.util.List<java.time.LocalDate> dates, java.util.List<BigDecimal> closes, String currency) {
        public boolean isEmpty() {
            return dates.isEmpty();
        }
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
    private final Map<String, History> histories = new ConcurrentHashMap<>();
    private final Map<String, Long> historyFetched = new ConcurrentHashMap<>();
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

    /**
     * Code Yahoo Finance d'un ISIN (recherche Yahoo, sans cle), pendant un import : appel direct, court ; vide hors
     * ligne ou si rien n'est trouve.
     */
    public Optional<String> resolve(String isin) {
        if (offline || isin == null || !isin.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) return Optional.empty();
        try {
            Matcher symbol = Pattern.compile("\"symbol\"\\s*:\\s*\"([A-Za-z0-9.^=-]{1,20})\"").matcher(source.fetch(SEARCH_URL.formatted(isin)));
            return symbol.find() ? Optional.of(symbol.group(1).toUpperCase(java.util.Locale.ROOT)) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.INFO, "Code de " + isin + " introuvable", exception);
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Recherche libre d'un titre (nom, ISIN, code) sur Yahoo Finance, a la demande de la personne : appel direct ;
     * vide hors ligne ou en cas de panne.
     */
    public java.util.List<Found> search(String query) {
        String wanted = query == null ? "" : query.strip();
        if (offline || wanted.isEmpty() || wanted.length() > 60) return java.util.List.of();
        try {
            return parseSearch(source.fetch(SEARCH_URL.formatted(URLEncoder.encode(wanted, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.INFO, "Recherche de titre indisponible", exception);
            return java.util.List.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return java.util.List.of();
        }
    }

    /** Resultats de la recherche Yahoo (objets « quotes ») : seuls les titres cotes avec un code valable. */
    static java.util.List<Found> parseSearch(String json) {
        int start = json.indexOf("\"quotes\"");
        if (start < 0) return java.util.List.of();
        int end = json.indexOf(']', start);
        Matcher object = Pattern.compile("\\{[^{}]*\\}").matcher(json.substring(start, end < 0 ? json.length() : end));
        java.util.List<Found> found = new java.util.ArrayList<>();
        while (object.find()) {
            String item = object.group();
            String symbol = field(item, "symbol");
            // Un indice (^…) ne s'achete pas : seuls les titres detenables sont proposes.
            if (symbol == null || symbol.startsWith("^") || !SYMBOL.matcher(symbol).matches() || "INDEX".equalsIgnoreCase(field(item, "quoteType"))) continue;
            String name = java.util.Objects.requireNonNullElse(field(item, "longname"), java.util.Objects.requireNonNullElse(field(item, "shortname"), symbol));
            found.add(new Found(symbol.toUpperCase(java.util.Locale.ROOT), name, java.util.Objects.requireNonNullElse(field(item, "exchDisp"), ""),
                    java.util.Objects.requireNonNullElse(field(item, "typeDisp"), "")));
        }
        return found;
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\u0026", "&").replace("\\\"", "\"").strip() : null;
    }

    /**
     * Historique de cours de {@code symbol} sur {@code range} (Yahoo « chart »), garde 6 heures. Appel direct a la
     * premiere demande ; vide hors ligne ou si le titre est inconnu.
     */
    public History history(String symbol, Range range) {
        History none = new History(java.util.List.of(), java.util.List.of(), "EUR");
        if (offline || symbol == null || !SYMBOL.matcher(symbol).matches()) return none;
        String key = symbol.toUpperCase(java.util.Locale.ROOT) + "|" + range;
        History cached = histories.get(key);
        if (cached != null && clock.millis() < historyFetched.getOrDefault(key, 0L) + HISTORY_REFRESH.toMillis()) return cached;
        try {
            History fresh = parseHistory(source.fetch(HISTORY_URL.formatted(URLEncoder.encode(symbol.toUpperCase(java.util.Locale.ROOT), StandardCharsets.UTF_8),
                    range.range, range.interval)));
            histories.put(key, fresh);
            historyFetched.put(key, clock.millis());
            return fresh;
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.INFO, "Historique de " + symbol + " indisponible", exception);
            return cached != null ? cached : none;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return cached != null ? cached : none;
        }
    }

    /** {@code timestamp} et {@code close} de la reponse « chart » ; les jours sans cours sont ignores. */
    static History parseHistory(String json) {
        Matcher times = Pattern.compile("\"timestamp\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        Matcher closes = Pattern.compile("\"close\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!times.find() || !closes.find()) throw new IllegalArgumentException("Historique absent de la réponse.");
        Matcher currency = Pattern.compile("\"currency\"\\s*:\\s*\"([A-Za-z]{3})\"").matcher(json);
        String code = currency.find() ? currency.group(1) : "EUR";
        boolean pence = code.equals("GBp") || code.equals("GBX");
        String[] stamps = times.group(1).split(",");
        String[] values = closes.group(1).split(",");
        java.util.List<java.time.LocalDate> dates = new java.util.ArrayList<>();
        java.util.List<BigDecimal> prices = new java.util.ArrayList<>();
        for (int index = 0; index < Math.min(stamps.length, values.length); index++) {
            String value = values[index].strip();
            if (value.isEmpty() || value.equals("null") || stamps[index].isBlank()) continue;
            BigDecimal price = new BigDecimal(value);
            if (price.signum() <= 0) continue;
            java.time.LocalDate date = java.time.Instant.ofEpochSecond(Long.parseLong(stamps[index].strip())).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            if (!dates.isEmpty() && dates.getLast().equals(date)) {
                prices.set(prices.size() - 1, pence ? price.movePointLeft(2) : price);
                continue;
            }
            dates.add(date);
            prices.add(pence ? price.movePointLeft(2) : price);
        }
        return new History(dates, prices, pence ? "GBP" : code.toUpperCase(java.util.Locale.ROOT));
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
        String code = currency.find() ? currency.group(1) : "EUR";
        // Londres cote souvent en pence (GBp) : ramene en livres.
        if (code.equals("GBp") || code.equals("GBX")) return new Quote(value.movePointLeft(2), "GBP");
        return new Quote(value, code.toUpperCase(java.util.Locale.ROOT));
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
