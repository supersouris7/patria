package fr.patria.contribution;

import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.property.Property;
import fr.patria.property.PropertyController;
import fr.patria.property.PropertyRepository;
import fr.patria.ui.Notice;
import fr.patria.web.FormData;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/** Ajout, modification et suppression des apports d'un bien. */
public final class ContributionController {
    private final ContributionRepository contributions;
    private final PropertyRepository properties;
    private final Clock clock;

    public ContributionController(ContributionRepository contributions, PropertyRepository properties, Clock clock) {
        this.contributions = contributions;
        this.properties = properties;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/bien/{id}/apports/nouveau", this::newForm);
        router.post("/bien/{id}/apports", this::create);
        router.get("/apport/{id}", this::editForm);
        router.get("/apport/{id}/gerer", request -> Response.redirect("/apport/" + request.id()));
        router.post("/apport/{id}", this::update);
        router.post("/apport/{id}/supprimer", this::delete);
    }

    private Response newForm(Request request) {
        Property property = PropertyController.require(properties, request.id());
        return Response.page(ContributionPages.form(property, null, Map.of("date", LocalDate.now(clock).toString()), null, request.csrf()));
    }

    private Response create(Request request) {
        Property property = PropertyController.require(properties, request.id());
        try {
            contributions.insert(read(request.form(), 0, property.id()));
            return Response.redirect("/bien/" + property.id() + "?ok=apport");
        } catch (ValidationException exception) {
            return Response.invalid(ContributionPages.form(property, null, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response editForm(Request request) {
        Contribution contribution = require(request.id());
        return Response.page(ContributionPages.form(propertyOf(contribution), contribution, ContributionPages.values(contribution), null, request.csrf()));
    }

    private Response update(Request request) {
        Contribution current = require(request.id());
        try {
            contributions.update(read(request.form(), current.id(), current.propertyId()));
            return Response.redirect("/bien/" + current.propertyId() + "?ok=apport");
        } catch (ValidationException exception) {
            return Response.invalid(ContributionPages.form(propertyOf(current), current, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response delete(Request request) {
        Contribution contribution = require(request.id());
        contributions.delete(contribution.id());
        return Response.redirect("/bien/" + contribution.propertyId() + "?ok=supprime");
    }

    private static Contribution read(FormData form, long id, long propertyId) {
        return new Contribution(id, propertyId, form.requiredText("name", "Libellé", 100), form.positiveAmount("amount", "Montant"), form.date("date", "Date"));
    }

    private Contribution require(long id) {
        return contributions.find(id).orElseThrow(() -> new NotFoundException("Apport introuvable", "Cet apport n'existe pas."));
    }

    private Property propertyOf(Contribution contribution) {
        return PropertyController.require(properties, contribution.propertyId());
    }
}
