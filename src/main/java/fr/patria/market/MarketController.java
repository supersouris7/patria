package fr.patria.market;

import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.web.Response;
import fr.patria.web.Router;

/** Bouton « Actualiser » du prix du marche et suivi du calcul en arriere-plan. */
public final class MarketController {
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
        // Interroge par la fiche du bien pendant un calcul : « pending » puis « ready ».
        router.get("/bien/{id}/marche/etat", request ->
                new Response.Text(200, market.isComputing(request.id()) ? "pending" : "ready"));
    }
}
