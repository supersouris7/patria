// Copyright (c) 2026 William Iv. SPDX-License-Identifier: Elastic-2.0 (voir LICENSE)
package fr.patria.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Taux moyen actuel des credits immobiliers (BCE, API publique sans cle), garde en memoire et partage par tous les
 * foyers. Jamais bloquant : la premiere demande lance le telechargement en arriere-plan et renvoie vide en attendant ;
 * le taux est ensuite rafraichi une fois par jour (la statistique est mensuelle). Une panne garde le dernier taux connu.
 */
public final class HousingRates {
    static final String URL = "https://data-api.ecb.europa.eu/service/data/MIR/M.FR.B.A2C.P.R.A.2250.EUR.N?lastNObservations=1&format=csvdata";
    private static final Duration REFRESH = Duration.ofDays(1);
    private static final Duration RETRY = Duration.ofHours(1);
    private static final Logger LOG = Logger.getLogger(HousingRates.class.getName());

    /** Source du fichier CSV de la BCE ; remplacee en test. */
    @FunctionalInterface
    interface Source {
        String fetch() throws IOException, InterruptedException;
    }

    private final Source source;
    private final Clock clock;
    private final Executor background;
    private final AtomicBoolean fetching = new AtomicBoolean();
    private volatile HousingRate current;
    private volatile long nextAttempt;
    private boolean offline;

    /** {@code offline} : aucun appel a la BCE (OFFLINE=true) ; la comparaison des taux n'est pas affichee. */
    public HousingRates(Clock clock, boolean offline) {
        this(offline ? () -> { throw new IOException("Mode hors ligne"); } : HousingRates::download, clock, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "patria-taux");
            thread.setDaemon(true);
            return thread;
        }));
        this.offline = offline;
    }

    HousingRates(Source source, Clock clock, Executor background) {
        this.source = source;
        this.clock = clock;
        this.background = background;
    }

    /** Dernier taux connu ; vide tant qu'aucun n'a pu etre telecharge. */
    public Optional<HousingRate> current() {
        if (offline) return Optional.empty();
        if (clock.millis() >= nextAttempt && fetching.compareAndSet(false, true)) {
            background.execute(this::refresh);
        }
        return Optional.ofNullable(current);
    }

    private void refresh() {
        try {
            current = parse(source.fetch());
            nextAttempt = clock.millis() + REFRESH.toMillis();
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Taux des crédits immobiliers (BCE) indisponible, nouvel essai dans une heure", exception);
            nextAttempt = clock.millis() + RETRY.toMillis();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            fetching.set(false);
        }
    }

    /** Derniere observation du CSV SDMX de la BCE (colonnes TIME_PERIOD et OBS_VALUE). */
    static HousingRate parse(String csv) {
        List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() < 2) throw new IllegalArgumentException("Réponse BCE vide.");
        List<String> header = List.of(lines.getFirst().split(",", -1));
        int period = header.indexOf("TIME_PERIOD");
        int value = header.indexOf("OBS_VALUE");
        if (period < 0 || value < 0) throw new IllegalArgumentException("Colonnes BCE inattendues.");
        String[] last = lines.getLast().split(",", -1);
        BigDecimal rate = new BigDecimal(last[value].trim());
        if (rate.signum() <= 0 || rate.compareTo(BigDecimal.valueOf(20)) > 0) throw new IllegalArgumentException("Taux BCE invraisemblable : " + rate);
        return new HousingRate(rate, YearMonth.parse(last[period].trim()));
    }

    private static String download() throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20))
                .header("Accept", "text/csv").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("BCE : HTTP " + response.statusCode());
        return response.body();
    }
}
