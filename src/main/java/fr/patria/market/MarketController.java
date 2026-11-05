package fr.patria.market;

import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;

/** Bouton « Actualiser » du prix du marche et suivi du calcul en arriere-plan. */
public final class MarketController {
    /** Filtres de la page des ventes, lus dans l'adresse ; une valeur illisible est ignoree. */
    static SalesAnalysis.Filter filter(Request request) {
        return new SalesAnalysis.Filter(positiveInt(request.query("periode"), 1200), decimal(request.query("surface_min")), decimal(request.query("surface_max")),
                positiveInt(request.query("distance"), 100_000), SalesAnalysis.Sort.of(request.query("tri")));
    }

    private static Integer positiveInt(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 && value <= max ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static java.math.BigDecimal decimal(String raw) {
        if (raw == null) return null;
        java.math.BigDecimal value = fr.patria.common.InputRules.decimal(raw.trim().replace(',', '.'));
        return value == null || value.signum() <= 0 || value.compareTo(java.math.BigDecimal.valueOf(100_000)) > 0 ? null : value;
    }

    private final MarketService market;
    private final PropertyRepository properties;

    public MarketController(MarketService market, PropertyRepository properties) {
        this.market = market;
        this.properties = properties;
    }

    public void register(Router router) {
        router.post("/bien/{id}/marche", request -> {
            Property property = PropertyController.require(properties, request.id());
            market.refreshInBackground(property);
            return Response.redirect("/bien/" + property.id() + "#marche");
        });
        router.estateGet("/bien/{id}/marche/ventes", request -> {
            Property property = PropertyController.require(properties, request.id());
            return Response.page(MarketPages.sales(property, market.sales(property), filter(request), market.today(), request.csrf()));
        });
        router.estateGet("/bien/{id}/marche/ventes.pdf", request -> {
            Property property = PropertyController.require(properties, request.id());
            MarketSales sales = market.sales(property).orElseThrow(() -> new fr.patria.common.NotFoundException("Ventes indisponibles",
                    "Le détail des ventes n'est pas encore calculé pour ce bien."));
            return new Response.Download("ventes-" + fr.patria.ui.Format.slug(property.name()) + "-" + market.today() + ".pdf", "application/pdf",
                    MarketPages.report(property, sales, filter(request), market.today()));
        });
        // Interroge par la fiche du bien pendant un calcul : « pending » puis « ready ».
        router.estateGet("/bien/{id}/marche/etat", request ->
                new Response.Text(200, market.isComputing(request.id()) ? "pending" : "ready"));
    }
}
