package fr.patria.property;

import fr.patria.common.NotFoundException;
import fr.patria.common.ValidationException;
import fr.patria.ui.Notice;
import fr.patria.web.Request;
import fr.patria.web.Response;
import fr.patria.web.Router;
import java.time.Clock;
import java.time.LocalDate;

/** Creation, modification, suppression d'un bien et enregistrement de la valeur retenue. */
public final class PropertyController {
    private final PropertyRepository properties;
    private final Clock clock;

    public PropertyController(PropertyRepository properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void register(Router router) {
        router.get("/administration/bien/nouveau", this::newForm);
        router.post("/bien", this::create);
        router.get("/administration/bien/{id}", this::editForm);
        router.post("/administration/bien/{id}", this::update);
        router.post("/administration/bien/{id}/supprimer", this::delete);
        router.post("/bien/{id}/valeur", this::saveSelectedValue);
    }

    /** Bien existant ou page 404 ; utilise aussi par les autres modules. */
    public static Property require(PropertyRepository properties, long id) {
        return properties.find(id).orElseThrow(() -> new NotFoundException("Bien introuvable", "Ce bien n'existe pas."));
    }

    private Response newForm(Request request) {
        return Response.page(PropertyPages.form(null, PropertyForm.defaults(today()), null, request.csrf()));
    }

    private Response create(Request request) {
        try {
            long id = properties.insert(PropertyForm.read(request.form(), null, today()));
            return Response.redirect("/bien/" + id + "?ok=cree");
        } catch (ValidationException exception) {
            return Response.invalid(PropertyPages.form(null, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response editForm(Request request) {
        Property property = require(properties, request.id());
        return Response.page(PropertyPages.form(property, PropertyForm.values(property), request.confirmation(), request.csrf()));
    }

    private Response update(Request request) {
        Property current = require(properties, request.id());
        try {
            properties.update(PropertyForm.read(request.form(), current, today()));
            return Response.redirect("/bien/" + current.id() + "?ok=enregistre");
        } catch (ValidationException exception) {
            return Response.invalid(PropertyPages.form(current, request.form().values(), Notice.error(exception.getMessage()), request.csrf()));
        }
    }

    private Response delete(Request request) {
        properties.delete(require(properties, request.id()).id());
        return Response.redirect("/administration/biens?ok=supprime");
    }

    /** Appele en direct par le curseur de la fiche du bien (fetch). */
    private Response saveSelectedValue(Request request) {
        Property property = require(properties, request.id());
        properties.updateSelectedValue(property.id(), Valuation.scenarios(property).clamp(request.form().amount("value", "Valeur retenue")));
        return Response.noContent();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
