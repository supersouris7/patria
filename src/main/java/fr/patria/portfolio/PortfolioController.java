package fr.patria.portfolio;

import fr.patria.market.HousingRates;
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
    private final HousingRates rates;

    public PortfolioController(Portfolio portfolio, PropertyRepository properties, HousingRates rates) {
        this.portfolio = portfolio;
        this.properties = properties;
        this.rates = rates;
    }

    public void register(Router router) {
        router.estateGet("/immobilier", request -> Response.page(PortfolioPages.dashboard(portfolio.overview(), rates.current(), request.csrf())));
        router.estateGet("/bien/{id}", request -> Response.page(PortfolioPages.property(portfolio.property(request.id()), rates.current(), request.confirmation(), request.csrf())));
        router.estateGet("/bien/{id}/mensualites", this::months);
        router.estateGet("/immobilier.pdf", request -> new Response.Download("patrimoine-" + portfolio.today() + ".pdf", "application/pdf",
                PortfolioReports.overview(portfolio.overview(), rates.current(), portfolio.today())));
        router.estateGet("/bien/{id}/synthese.pdf", request -> {
            Portfolio.PropertySummary summary = portfolio.property(request.id());
            return new Response.Download(fr.patria.ui.Format.slug(summary.property().name()) + "-" + portfolio.today() + ".pdf", "application/pdf",
                    PortfolioReports.property(summary, rates.current(), portfolio.today()));
        });
        router.get("/administration/biens", request -> Response.page(PortfolioPages.administration(portfolio.overview(), request.confirmation(), request.csrf())));
        router.get("/administration/bien/{id}/financements", request -> Response.page(PortfolioPages.financing(portfolio.property(request.id()), request.confirmation(), request.csrf())));
    }

    private Response months(Request request) {
        Property property = PropertyController.require(properties, request.id());
        return Response.page(PortfolioPages.months(property, portfolio.months(property), request.csrf()));
    }
}
