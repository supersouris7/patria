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
        router.estateGet("/navigation/biens", request -> Response.page(fr.patria.ui.Html.concat(
                fr.patria.ui.Html.raw("<a href=\"/immobilier#biens\">Voir tous les biens</a>"),
                properties.all().stream().map(property -> fr.patria.ui.Html.of("<a href=\"/bien/%s\">%s</a>", property.id(), property.name())).collect(fr.patria.ui.Html.joining()))));
        router.estateGet("/immobilier", request -> Response.page(PortfolioPages.dashboard(portfolio.overview(), rates.current(), request.csrf())));
        router.estateGet("/bien/{id}", request -> Response.page(propertyPage(request)));
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

    private fr.patria.ui.Html propertyPage(Request request) {
        Portfolio.PropertySummary summary = portfolio.property(request.id());
        return PortfolioPages.property(summary, rates.current(), portfolio.sales(summary.property()).orElse(null), portfolio.today(), request.confirmation(), request.csrf());
    }

    private Response months(Request request) {
        Property property = PropertyController.require(properties, request.id());
        return Response.page(PortfolioPages.months(property, portfolio.months(property), request.csrf()));
    }
}
