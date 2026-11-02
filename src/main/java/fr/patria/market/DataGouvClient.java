package fr.patria.market;

import fr.patria.property.PropertyKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Acces aux donnees publiques officielles, sans cle d'API :
 * <ul>
 *   <li>API Adresse (Base Adresse Nationale) : https://api-adresse.data.gouv.fr</li>
 *   <li>DVF geolocalisees (DGFiP, Etalab) : https://files.data.gouv.fr/geo-dvf/</li>
 *   <li>Statistiques DVF mensuelles par departement (Etalab), via l'API tabulaire de data.gouv.fr</li>
 * </ul>
 */
final class DataGouvClient implements MarketData {
    private static final String ADDRESS_API = "https://api-adresse.data.gouv.fr/search/";
    private static final String DVF_FILES = "https://files.data.gouv.fr/geo-dvf/latest/csv/";
    /** Ressource « Statistiques mensuelles DVF » du jeu de donnees « Statistiques DVF ». */
    private static final String DVF_STATISTICS = "https://tabular-api.data.gouv.fr/api/resources/03fba98d-885b-43c0-8986-d299cabc29da/data/";
    private static final double MIN_ADDRESS_SCORE = 0.5;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    @Override
    public Optional<MarketAnalysis.Location> locate(String address, String commune) throws IOException {
        if (!address.isBlank()) {
            Optional<MarketAnalysis.Location> precise = search(address + " " + commune, false);
            if (precise.isPresent()) return precise;
        }
        return commune.isBlank() ? Optional.empty() : search(commune, true);
    }

    private Optional<MarketAnalysis.Location> search(String query, boolean municipality) throws IOException {
        String url = ADDRESS_API + "?limit=1&q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8) + (municipality ? "&type=municipality" : "");
        List<Object> features = Json.array(Json.object(Json.parse(get(url).orElse("{\"features\":[]}"))).get("features"));
        if (features.isEmpty()) return Optional.empty();
        Map<String, Object> feature = Json.object(features.getFirst());
        Map<String, Object> properties = Json.object(feature.get("properties"));
        if (((BigDecimal) properties.get("score")).doubleValue() < MIN_ADDRESS_SCORE) return Optional.empty();
        List<Object> coordinates = Json.array(Json.object(feature.get("geometry")).get("coordinates"));
        boolean precise = !"municipality".equals(properties.get("type"));
        return Optional.of(new MarketAnalysis.Location(((BigDecimal) coordinates.get(1)).doubleValue(), ((BigDecimal) coordinates.get(0)).doubleValue(),
                (String) properties.get("citycode"), (String) properties.get("label"), precise));
    }

    @Override
    public Optional<String> sales(String citycode, int year) throws IOException {
        return get(DVF_FILES + year + "/communes/" + MarketService.department(citycode) + "/" + citycode + ".csv");
    }

    @Override
    public List<PriceIndex.Point> departmentIndex(String department, PropertyKind kind, YearMonth from) throws IOException {
        String suffix = kind == PropertyKind.HOUSE ? "maison" : "appartement";
        List<PriceIndex.Point> points = new ArrayList<>();
        String url = DVF_STATISTICS + "?echelle_geo__exact=departement&code_geo__exact=" + department
                + "&annee_mois__greater=" + from.minusMonths(1) + "&page_size=100&columns=annee_mois,med_prix_m2_" + suffix + ",nb_ventes_" + suffix;
        for (int page = 0; url != null && page < 10; page++) {
            // API lente a froid (jusqu'a 30 s) : delai plus long que pour les fichiers.
            Map<String, Object> response = Json.object(Json.parse(get(url, Duration.ofSeconds(45)).orElse("{\"data\":[]}")));
            for (Object row : Json.array(response.get("data"))) {
                Map<String, Object> values = Json.object(row);
                if (values.get("med_prix_m2_" + suffix) instanceof BigDecimal median && values.get("nb_ventes_" + suffix) instanceof BigDecimal count) {
                    points.add(new PriceIndex.Point(YearMonth.parse((String) values.get("annee_mois")), median, count.intValue()));
                }
            }
            Object links = response.get("links");
            url = links instanceof Map<?, ?> map && map.get("next") instanceof String next ? next : null;
        }
        return points;
    }

    /** GET ; vide si 404, exception pour toute autre erreur. */
    private Optional<String> get(String url) throws IOException {
        return get(url, Duration.ofSeconds(30));
    }

    private Optional<String> get(String url, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("User-Agent", "Patria (suivi immobilier auto-heberge)").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " sur " + url);
            return Optional.of(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Requête interrompue", exception);
        }
    }
}
