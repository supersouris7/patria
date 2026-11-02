package fr.patria.portfolio;

import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;

/** Pages de consultation : vue d'ensemble, fiche d'un bien, mensualites, administration. */
public final class PortfolioController {
    private final Portfolio portfolio;
    private final PropertyRepository properties;

    public PortfolioController(Portfolio portfolio, PropertyRepository properties) {
        this.portfolio = portfolio;
        this.properties = properties;
    }

    public void register(Router router) {
        router.get("/", request -> Response.page(PortfolioPages.dashboard(portfolio.overview(), request.csrf())));
        router.get("/bien/{id}", request -> Response.page(PortfolioPages.property(portfolio.property(request.id()), request.confirmation(), request.csrf())));
        router.get("/bien/{id}/mensualites", this::months);
        router.get("/administration", request -> Response.page(PortfolioPages.administration(portfolio.overview(), request.confirmation(), request.csrf())));
        router.get("/administration/bien/{id}/financements", request -> Response.page(PortfolioPages.financing(portfolio.property(request.id()), request.confirmation(), request.csrf())));
    }

    private Response months(Request request) {
        Property property = PropertyController.require(properties, request.id());
        return Response.page(PortfolioPages.months(property, portfolio.months(property), request.csrf()));
    }
}
