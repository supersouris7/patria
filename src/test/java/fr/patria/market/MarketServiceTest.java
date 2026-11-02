package fr.patria.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.patria.TestData;
import fr.patria.property.Property;
import fr.patria.property.PropertyKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketServiceTest {
    @TempDir
    Path directory;

    /** Fausse source : 12 ventes d'appartements a 4 000 €/m² autour du bien, pour chaque annee publiee. */
    private static final class FakeData implements MarketData {
        final List<String> requested = new ArrayList<>();
        String citycode = "33009";
        boolean failing;
        boolean indexFailing;

        @Override
        public Optional<MarketAnalysis.Location> locate(String address, String commune) throws IOException {
            if (failing) throw new IOException("réseau coupé");
            return Optional.of(new MarketAnalysis.Location(44.64, -1.2, citycode, "Arcachon", !address.isBlank()));
        }

        @Override
        public Optional<String> sales(String code, int year) {
            requested.add(code + "/" + year);
            if (year > 2025) return Optional.empty();
            StringBuilder csv = new StringBuilder("id_mutation,date_mutation,nature_mutation,valeur_fonciere,type_local,surface_reelle_bati,longitude,latitude\n");
            for (int i = 0; i < 12; i++) csv.append(year).append('-').append(code).append('-').append(i).append(',').append(year).append("-03-01,Vente,240000,Appartement,60,-1.2,44.64\n");
            return Optional.of(csv.toString());
        }

        @Override
        public List<PriceIndex.Point> departmentIndex(String department, PropertyKind kind, YearMonth from) throws IOException {
            if (indexFailing) throw new IOException("504");
            return List.of(new PriceIndex.Point(YearMonth.of(2025, 6), new BigDecimal("4000"), 100));
        }
    }

    private Property property(long id, String address, String commune) {
        return new Property(id, "Bien", PropertyKind.APARTMENT, commune, address, new BigDecimal("60"), new BigDecimal("200000"),
                LocalDate.of(2020, 1, 1), null, null, LocalDate.of(2020, 1, 1), new BigDecimal("0.08"), null);
    }

    @Test
    void computesOnceThenServesFromCacheUntilPropertyChanges() {
        TestData.Stores stores = TestData.stores(directory);
        long id = stores.properties().insert(property(0, "1 rue du Port", "Arcachon"));
        FakeData data = new FakeData();
        MarketService service = new MarketService(data, new MarketRepository(stores.database()), TestData.CLOCK, Runnable::run, Runnable::run);

        MarketPrice first = service.forProperty(property(id, "1 rue du Port", "Arcachon")).orElseThrow();
        assertEquals(MarketPrice.Status.OK, first.status());
        assertEquals(0, new BigDecimal("4000").compareTo(first.median()));
        assertEquals("2023–2025", first.period(), "trois dernières années publiées");
        int requests = data.requested.size();

        service.forProperty(property(id, "1 rue du Port", "Arcachon"));
        assertEquals(requests, data.requested.size(), "deuxième affichage servi depuis la base");

        service.forProperty(property(id, "8 rue du Port", "Arcachon"));
        assertTrue(data.requested.size() > requests, "adresse modifiée : nouveau calcul");
        assertEquals(1, service.cached(List.of(id)).size());
        stores.database().close();
    }

    @Test
    void slowIndexDoesNotPreventThePriceButIsRetriedTheNextDay() {
        TestData.Stores stores = TestData.stores(directory);
        long id = stores.properties().insert(property(0, "1 rue du Port", "Arcachon"));
        FakeData data = new FakeData();
        data.indexFailing = true;
        MarketService service = new MarketService(data, new MarketRepository(stores.database()), TestData.CLOCK, Runnable::run, Runnable::run);

        MarketPrice price = service.forProperty(property(id, "1 rue du Port", "Arcachon")).orElseThrow();

        assertEquals(MarketPrice.Status.OK, price.status());
        assertEquals(null, price.indexMonth(), "prix non actualisé");
        stores.database().close();
    }

    @Test
    void networkFailureKeepsPreviousResult() {
        TestData.Stores stores = TestData.stores(directory);
        long id = stores.properties().insert(property(0, "1 rue du Port", "Arcachon"));
        FakeData data = new FakeData();
        MarketService service = new MarketService(data, new MarketRepository(stores.database()), TestData.CLOCK, Runnable::run, Runnable::run);
        service.forProperty(property(id, "1 rue du Port", "Arcachon"));

        data.failing = true;
        service.refreshInBackground(property(id, "1 rue du Port", "Arcachon"));

        assertEquals(MarketPrice.Status.OK, service.cached(List.of(id)).get(id).status());
        stores.database().close();
    }

    @Test
    void pageGetsPendingResultWhileComputationRunsInBackground() {
        TestData.Stores stores = TestData.stores(directory);
        long id = stores.properties().insert(property(0, "1 rue du Port", "Arcachon"));
        List<Runnable> queued = new ArrayList<>();
        MarketService service = new MarketService(new FakeData(), new MarketRepository(stores.database()), TestData.CLOCK, queued::add, Runnable::run);

        assertEquals(MarketPrice.Status.PENDING, service.forProperty(property(id, "1 rue du Port", "Arcachon")).orElseThrow().status());
        assertTrue(service.isComputing(id));
        service.forProperty(property(id, "1 rue du Port", "Arcachon"));
        assertEquals(1, queued.size(), "un seul calcul lancé à la fois");

        queued.getFirst().run();
        assertEquals(MarketPrice.Status.OK, service.forProperty(property(id, "1 rue du Port", "Arcachon")).orElseThrow().status());
        stores.database().close();
    }

    @Test
    void alsaceMoselleIsNotCoveredAndParisUsesArrondissements() {
        TestData.Stores stores = TestData.stores(directory);
        long id = stores.properties().insert(property(0, "", "Strasbourg"));
        FakeData data = new FakeData();
        MarketService service = new MarketService(data, new MarketRepository(stores.database()), TestData.CLOCK, Runnable::run, Runnable::run);

        data.citycode = "67482";
        assertEquals(MarketPrice.Status.UNAVAILABLE, service.compute(property(id, "", "Strasbourg")).status());

        data.citycode = "75056";
        MarketPrice paris = service.compute(property(id, "", "Paris"));
        assertEquals(MarketPrice.Scope.COMMUNE, paris.scope());
        assertTrue(data.requested.contains("75120/2025"), "les 20 arrondissements sont interrogés");
        stores.database().close();
    }
}
