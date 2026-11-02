package fr.patria.market;

import fr.patria.property.Property;
import fr.patria.storage.Database;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.time.YearMonth;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * Prix au m² du marche autour de chaque bien, garde en base 30 jours.
 *
 * <p>Le calcul (quelques telechargements data.gouv.fr, plusieurs secondes) est lance <b>en arriere-plan</b> a
 * l'ouverture de la fiche d'un bien, si le resultat est absent, vieux de plus de 30 jours, ou si le type, la surface
 * ou l'adresse du bien ont change. La page s'affiche aussitot (avec l'ancien resultat ou « calcul en cours ») et se
 * recharge quand le calcul est termine. La vue d'ensemble se contente du resultat en base.
 */
public final class MarketService {
    static final int CACHE_DAYS = 30;
    static final int RETRY_DAYS_AFTER_ERROR = 1;
    static final int YEARS = 3;
    private static final Logger LOG = Logger.getLogger(MarketService.class.getName());
    /** Departements absents de DVF (livre foncier d'Alsace-Moselle, Mayotte). */
    private static final List<String> NOT_COVERED = List.of("57", "67", "68", "976");
    /** Paris, Lyon et Marseille : DVF est publie par arrondissement. */
    private static final Map<String, List<String>> ARRONDISSEMENTS = Map.of(
            "75056", codes(75101, 75120), "69123", codes(69381, 69389), "13055", codes(13201, 13216));

    private record CachedIndex(LocalDate fetchedOn, List<PriceIndex.Point> points) {
    }

    private final MarketData data;
    private final MarketRepository repository;
    private final Clock clock;
    private final Executor background;
    private final Executor downloads;
    private final Set<Long> computing = ConcurrentHashMap.newKeySet();
    /** Indice departemental garde une journee : partage par les biens d'un meme departement. */
    private final Map<String, CachedIndex> indexes = new ConcurrentHashMap<>();

    public MarketService(Database database, Clock clock) {
        this(new DataGouvClient(), new MarketRepository(database), clock, Executors.newSingleThreadExecutor(MarketService::daemon),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** {@code background} execute les calculs ; en test, {@code Runnable::run} les rend synchrones. */
    MarketService(MarketData data, MarketRepository repository, Clock clock, Executor background, Executor downloads) {
        this.data = data;
        this.repository = repository;
        this.clock = clock;
        this.background = background;
        this.downloads = downloads;
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "patria-marche");
        thread.setDaemon(true);
        return thread;
    }

    /** Resultat en base, sans appel reseau (vue d'ensemble). */
    public Map<Long, MarketPrice> cached(Collection<Long> propertyIds) {
        return repository.findAll(propertyIds);
    }

    /**
     * Resultat pour la fiche d'un bien. S'il est absent ou perime, un recalcul est lance en arriere-plan ; en attendant,
     * l'ancien resultat valable est renvoye, sinon un resultat « en cours ». Vide si le bien n'a ni adresse ni commune.
     */
    public Optional<MarketPrice> forProperty(Property property) {
        if (property.address().isBlank() && property.commune().isBlank()) return Optional.empty();
        Optional<MarketPrice> current = currentFor(property);
        if (current.isEmpty() || !isFresh(current.get())) {
            refreshInBackground(property);
            current = currentFor(property);
        }
        boolean waiting = computing.contains(property.id()) && (current.isEmpty() || !current.get().isAvailable());
        if (waiting || current.isEmpty()) {
            return Optional.of(MarketPrice.failure(property.id(), fingerprint(property), today(), MarketPrice.Status.PENDING, "Calcul du prix du marché en cours…"));
        }
        return current;
    }

    /** Dernier resultat calcule avec les caracteristiques actuelles du bien. */
    private Optional<MarketPrice> currentFor(Property property) {
        String fingerprint = fingerprint(property);
        return repository.find(property.id()).filter(price -> price.fingerprint().equals(fingerprint));
    }

    /** Vrai tant qu'un calcul est en cours pour ce bien. */
    public boolean isComputing(long propertyId) {
        return computing.contains(propertyId);
    }

    /** Recalcul en arriere-plan (bouton « Actualiser » ou resultat perime). */
    public void refreshInBackground(Property property) {
        if (!computing.add(property.id())) return;
        background.execute(() -> {
            try {
                MarketPrice previous = repository.find(property.id()).orElse(null);
                MarketPrice result = compute(property);
                // Une panne reseau n'efface pas un resultat valable : on le garde et on reessaiera plus tard.
                boolean keepPrevious = result.status() == MarketPrice.Status.ERROR && previous != null && previous.isAvailable()
                        && previous.fingerprint().equals(result.fingerprint());
                if (!keepPrevious) repository.save(result);
            } catch (RuntimeException exception) {
                LOG.log(Level.WARNING, "Enregistrement du prix du marché impossible pour le bien " + property.id(), exception);
            } finally {
                computing.remove(property.id());
            }
        });
    }

    private boolean isFresh(MarketPrice price) {
        boolean incomplete = price.status() == MarketPrice.Status.ERROR || (price.isAvailable() && price.indexMonth() == null);
        int days = incomplete ? RETRY_DAYS_AFTER_ERROR : CACHE_DAYS;
        return !price.computedOn().plusDays(days).isBefore(today());
    }

    MarketPrice compute(Property property) {
        String fingerprint = fingerprint(property);
        LocalDate today = today();
        try {
            Optional<MarketAnalysis.Location> location = data.locate(property.address(), property.commune());
            if (location.isEmpty()) return MarketPrice.failure(property.id(), fingerprint, today, MarketPrice.Status.UNAVAILABLE, "Adresse ou commune introuvable.");
            String department = department(location.get().citycode());
            if (NOT_COVERED.contains(department)) {
                return MarketPrice.failure(property.id(), fingerprint, today, MarketPrice.Status.UNAVAILABLE,
                        "Les ventes de ce département ne sont pas publiées dans DVF (Alsace-Moselle, Mayotte).");
            }
            List<String> communes = ARRONDISSEMENTS.getOrDefault(location.get().citycode(), List.of(location.get().citycode()));
            // L'indice (API lente) est demande en parallele des fichiers de ventes.
            YearMonth indexFrom = YearMonth.of(today.getYear() - YEARS - 1, 1);
            // Indice indisponible : le prix reste calcule, sans actualisation, et un nouvel essai a lieu le lendemain.
            CompletableFuture<List<PriceIndex.Point>> indexPoints = CompletableFuture.supplyAsync(() -> departmentIndex(department, property.kind(), indexFrom), downloads)
                    .exceptionally(failure -> {
                        LOG.log(Level.WARNING, "Indice DVF du département " + department + " indisponible, prix non actualisé", failure);
                        return List.of();
                    });
            List<Integer> candidateYears = IntStream.rangeClosed(today.getYear() - YEARS - 2, today.getYear()).boxed().sorted(java.util.Comparator.reverseOrder()).toList();
            Map<Integer, List<CompletableFuture<Optional<String>>>> files = new java.util.LinkedHashMap<>();
            for (int year : candidateYears) {
                files.put(year, communes.stream().map(commune -> CompletableFuture.supplyAsync(() -> download(commune, year), downloads)).toList());
            }
            List<Sale> sales = new ArrayList<>();
            List<Integer> years = new ArrayList<>();
            for (Map.Entry<Integer, List<CompletableFuture<Optional<String>>>> year : files.entrySet()) {
                if (years.size() >= YEARS) break;
                boolean published = false;
                for (CompletableFuture<Optional<String>> file : year.getValue()) {
                    Optional<String> csv = file.join();
                    if (csv.isPresent()) {
                        published = true;
                        sales.addAll(DvfSales.parse(csv.get()));
                    }
                }
                if (published) years.add(year.getKey());
            }
            if (years.isEmpty()) return MarketPrice.failure(property.id(), fingerprint, today, MarketPrice.Status.UNAVAILABLE, "Aucune vente publiée pour cette commune.");
            PriceIndex index = new PriceIndex(indexPoints.join());
            String period = years.size() == 1 ? String.valueOf(years.getFirst()) : years.getLast() + "–" + years.getFirst();
            return MarketAnalysis.analyse(property.id(), fingerprint, today, property.kind(), property.area(), location.get(),
                    location.get().label(), sales, index, period);
        } catch (IOException | RuntimeException exception) {
            Throwable cause = exception instanceof CompletionException && exception.getCause() != null ? exception.getCause() : exception;
            LOG.log(Level.WARNING, "Prix du marché indisponible pour le bien " + property.id(), cause);
            return MarketPrice.failure(property.id(), fingerprint, today, MarketPrice.Status.ERROR,
                    "Données publiques momentanément injoignables. Nouvel essai automatique demain.");
        }
    }

    private Optional<String> download(String commune, int year) {
        try {
            return data.sales(commune, year);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private List<PriceIndex.Point> departmentIndex(String department, fr.patria.property.PropertyKind kind, YearMonth from) {
        String key = department + "|" + kind + "|" + from;
        CachedIndex cached = indexes.get(key);
        if (cached != null && cached.fetchedOn().equals(today())) return cached.points();
        try {
            List<PriceIndex.Point> points = data.departmentIndex(department, kind, from);
            indexes.put(key, new CachedIndex(today(), points));
            return points;
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    static String fingerprint(Property property) {
        return String.join("|", property.kind().name(), property.area() == null ? "" : property.area().stripTrailingZeros().toPlainString(),
                property.address().trim().toLowerCase(), property.commune().trim().toLowerCase());
    }

    /** Code du departement d'une commune : 2 caracteres (dont 2A/2B), 3 en outre-mer. */
    static String department(String citycode) {
        return citycode.startsWith("97") ? citycode.substring(0, 3) : citycode.substring(0, 2);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static List<String> codes(int first, int last) {
        return IntStream.rangeClosed(first, last).mapToObj(String::valueOf).toList();
    }
}
